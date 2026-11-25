package com.mechanicalaxiom.notetrail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Random

class GroupViewModel(application: Application) : AndroidViewModel(application) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "GroupViewModel"

    private val _state = MutableLiveData<GroupState>()
    val state: LiveData<GroupState> = _state

    private var lastCreateTime = 0L

    init {
        loadGroupInfo()
    }

    fun loadGroupInfo(subjectFilter: String? = null) {
        Log.d(TAG, "loadGroupInfo called with filter: $subjectFilter")
        viewModelScope.launch {
            _state.postValue(GroupState.Loading)
            try {
                val userId = auth.currentUser?.uid ?: run {
                    Log.d(TAG, "No user logged in")
                    return@launch _state.postValue(GroupState.NoGroup)
                }
                Log.d(TAG, "Fetching user doc for: $userId")
                val userDoc = db.collection("users").document(userId).get().await()
                
                if (!userDoc.exists()) {
                    Log.d(TAG, "User doc does not exist, creating one")
                    val newUser = hashMapOf(
                        "email" to auth.currentUser?.email,
                        "groupIds" to emptyList<String>(),
                        "lastActive" to System.currentTimeMillis()
                    )
                    db.collection("users").document(userId).set(newUser).await()
                    Log.d(TAG, "User doc created successfully")
                    _state.postValue(GroupState.NoGroup)
                    return@launch
                }

                val rawGroupIds = (userDoc.get("groupIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val encryptedGroupIds = (userDoc.get("encryptedGroupIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                
                val groupIds = mutableSetOf<String>()
                groupIds.addAll(rawGroupIds)
                
                // Decrypt IDs from the secure list
                if (CryptoManager.hasCloudMasterKey()) {
                    for (encId in encryptedGroupIds) {
                        try {
                            val decId = CryptoManager.decrypt(encId)
                            groupIds.add(decId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decrypt groupId: $encId", e)
                        }
                    }
                }

                Log.d(TAG, "Resolved groupIds: $groupIds")

                if (groupIds.isEmpty()) {
                    Log.d(TAG, "No groups found for user")
                    _state.postValue(GroupState.NoGroup)
                    return@launch
                }

                val groups = mutableListOf<Group>()
                val leaderboardMap = mutableMapOf<String, List<LeaderboardEntry>>()

                // Fetch groups in parallel
                val groupDeferred = groupIds.map { groupId ->
                    async {
                        try {
                            val groupDoc = withTimeout(5000) {
                                db.collection("groups").document(groupId).get().await()
                            }
                            if (groupDoc.exists()) {
                                val group = groupDoc.toObject(Group::class.java)
                                if (group != null) {
                                    val leaderboard = fetchLeaderboard(group, userId, subjectFilter)
                                    group.copy(leaderboard = leaderboard)
                                } else null
                            } else null
                        } catch (e: Exception) {
                            Log.e(TAG, "Timeout or error fetching group $groupId", e)
                            null
                        }
                    }
                }
                
                val fetchedGroups = groupDeferred.awaitAll().filterNotNull()
                groups.addAll(fetchedGroups)
                fetchedGroups.forEach { leaderboardMap[it.id] = it.leaderboard }
                
                if (groups.isEmpty()) {
                    Log.d(TAG, "All resolved group IDs were invalid or missing")
                    _state.postValue(GroupState.NoGroup)
                } else {
                    Log.d(TAG, "Setting state to InGroups with ${groups.size} groups")
                    _state.postValue(GroupState.InGroups(groups, leaderboardMap))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading group info", e)
                _state.postValue(GroupState.Error("Failed to sync leaderboard. Error: ${e.message}"))
            }
        }
    }

    private suspend fun fetchLeaderboard(group: Group, currentUserId: String, subjectFilter: String?): List<LeaderboardEntry> {
        Log.d(TAG, "fetchLeaderboard for group: ${group.id}")
        val members = db.collection("groups").document(group.id).collection("members").get().await()
        Log.d(TAG, "fetchLeaderboard: found ${members.size()} members")

        val startOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Fetch all members in parallel
        val entries = viewModelScope.async {
            members.map { member ->
                async {
                    val uid = member.id
                    try {
                        withTimeout(3000) {
                            val userDoc = db.collection("users").document(uid).get().await()
                            if (userDoc.exists()) {
                                val name = userDoc.getString("name") ?: userDoc.getString("email")?.substringBefore("@") ?: "Student"
                                val lastActive = userDoc.getLong("lastActive") ?: 0L
                                
                                val progressDoc = db.collection("users").document(uid).collection("progress").document("data").get().await()
                                val progressData = progressDoc.data as? Map<String, Any> ?: emptyMap()
                                
                                var totalO = 0.0
                                var totalP = 0.0
                                var count = 0
                                var countToday = 0

                                progressData.forEach { (paperKey, value) ->
                                    if (subjectFilter != null && !paperKey.startsWith(subjectFilter)) return@forEach

                                    val dataMap = value as? Map<*, *> ?: return@forEach
                                    
                                    val encryptedMeta = dataMap["encryptedMeta"] as? String
                                    val data = if (encryptedMeta != null && uid == currentUserId && CryptoManager.hasCloudMasterKey()) {
                                        try {
                                            val dec = CryptoManager.decrypt(encryptedMeta)
                                            com.google.gson.Gson().fromJson(dec, Map::class.java)
                                        } catch (e: Exception) { dataMap }
                                    } else {
                                        dataMap
                                    }

                                    if (data["isCompleted"] == true) {
                                        val o = (data["marksObtained"] as? Number)?.toDouble() ?: 0.0
                                        val t = (data["totalMarks"] as? Number)?.toDouble() ?: 0.0
                                        val timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L

                                        if (t > 0) {
                                            totalO += o
                                            totalP += t
                                            count++
                                            if (timestamp >= startOfDay) {
                                                countToday++
                                            }
                                        }
                                    }
                                }

                                val avg = if (totalP > 0) (totalO / totalP) * 100 else 0.0
                                LeaderboardEntry(uid, name, avg, count, countToday, isCurrentUser = (uid == currentUserId), lastActive = lastActive)
                            } else null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Timeout or error fetching member data for $uid", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMutableList()
        }.await()

        if (entries.isNotEmpty()) {
            entries.sortWith(compareByDescending<LeaderboardEntry> { it.averageScore }.thenByDescending { it.papersSolved })
            val maxSolved = entries.maxOf { it.papersSolved }
            entries.forEachIndexed { index, entry ->
                entry.rank = index + 1
                when {
                    entry.rank == 1 && entry.papersSolved > 0 -> entry.badge = "👑 Scholar"
                    entry.papersSolved == maxSolved && maxSolved > 2 -> entry.badge = "🔥 Grinder"
                    entry.averageScore > 85.0 -> entry.badge = "⭐ A* Club"
                }
            }
        }
        return entries
    }

    suspend fun getSubjectComparison(groupId: String): List<SubjectComparison> {
        Log.d(TAG, "getSubjectComparison called for group: $groupId")
        val currentUserId = auth.currentUser?.uid ?: return emptyList()
        val members = db.collection("groups").document(groupId).collection("members").get().await()
        
        // Subject -> PaperId -> List of percentages
        val subjectPaperData = mutableMapOf<String, MutableMap<String, MutableList<Double>>>()
        // Subject -> PaperId -> User percentage
        val userPaperData = mutableMapOf<String, MutableMap<String, Double>>()

        // Fetch all members' progress in parallel
        viewModelScope.async {
            members.map { member ->
                async {
                    val uid = member.id
                    try {
                        withTimeout(3000) {
                            val progressDoc = db.collection("users").document(uid).collection("progress").document("data").get().await()
                            val progressData = progressDoc.data as? Map<String, Any> ?: emptyMap()
                            
                            progressData.forEach { (paperKey, value) ->
                                val subjectCode = paperKey.substringBefore("_")
                                val dataMap = value as? Map<*, *> ?: return@forEach
                                
                                val encryptedMeta = dataMap["encryptedMeta"] as? String
                                val data = if (encryptedMeta != null && uid == currentUserId && CryptoManager.hasCloudMasterKey()) {
                                    try {
                                        val dec = CryptoManager.decrypt(encryptedMeta)
                                        com.google.gson.Gson().fromJson(dec, Map::class.java)
                                    } catch (e: Exception) { dataMap }
                                } else {
                                    dataMap
                                }

                                if (data["isCompleted"] == true) {
                                    val o = (data["marksObtained"] as? Number)?.toDouble() ?: 0.0
                                    val t = (data["totalMarks"] as? Number)?.toDouble() ?: 0.0
                                    if (t > 0) {
                                        val percentage = (o / t) * 100
                                        val paperId = paperKey.substringAfterLast("_") // e.g., "qp_42"
                                        
                                        synchronized(subjectPaperData) {
                                            val papers = subjectPaperData.getOrPut(subjectCode) { mutableMapOf() }
                                            papers.getOrPut(paperId) { mutableListOf() }.add(percentage)
                                        }
                                        
                                        if (uid == currentUserId) {
                                            synchronized(userPaperData) {
                                                val papers = userPaperData.getOrPut(subjectCode) { mutableMapOf() }
                                                papers[paperId] = percentage
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Timeout or error fetching comparison data for $uid", e)
                    }
                }
            }.awaitAll()
        }.await()

        return subjectPaperData.map { (subject, papersMap) ->
            val paperComparisons = papersMap.map { (paperId, percentages) ->
                PaperComparison(
                    paperId = paperId,
                    paperName = "Paper ${paperId.substringAfterLast("qp_")}",
                    groupAverage = percentages.average(),
                    userAverage = userPaperData[subject]?.get(paperId) ?: 0.0,
                    totalAttempts = percentages.size
                )
            }.sortedBy { it.paperId }

            SubjectComparison(
                subjectCode = subject,
                groupAverage = paperComparisons.map { it.groupAverage }.average(),
                userAverage = paperComparisons.filter { it.userAverage > 0 }.map { it.userAverage }.let { 
                    if (it.isEmpty()) 0.0 else it.average() 
                },
                papers = paperComparisons
            )
        }.sortedByDescending { it.papers.size }
    }

    private fun generateGroupId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars[Random().nextInt(chars.length)] }.joinToString("")
    }

    fun createGroup(name: String, pin: String?, isPrivate: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCreateTime < 60000) {
            Log.d(TAG, "Create group rate limited")
            _state.postValue(GroupState.Error("Wait 1 minute before creating another group"))
            return
        }
        lastCreateTime = currentTime

        Log.d(TAG, "Creating group: $name")
        viewModelScope.launch {
            val userId = auth.currentUser?.uid ?: run {
                Log.e(TAG, "Cannot create group: User not logged in")
                return@launch
            }
            val userDocRef = db.collection("users").document(userId)
            val gid = generateGroupId() // 6-character unique ID
            val group = Group(gid, name, userId, pin, isPrivate)

            Log.d(TAG, "Starting transaction for group creation: $gid")
            db.runTransaction { transaction ->
                val userDoc = transaction.get(userDocRef)
                
                transaction.set(db.collection("groups").document(gid), group)
                transaction.set(db.collection("groups").document(gid).collection("members").document(userId), mapOf("uid" to userId))
                
                if (userDoc.exists()) {
                    val userUpdate = mutableMapOf<String, Any>()
                    if (CryptoManager.hasCloudMasterKey()) {
                        userUpdate["encryptedGroupIds"] = FieldValue.arrayUnion(CryptoManager.encrypt(gid))
                        userUpdate["groupIds"] = FieldValue.delete() // Wipe legacy plain field
                    } else {
                        userUpdate["groupIds"] = FieldValue.arrayUnion(gid)
                    }
                    transaction.update(userDocRef, userUpdate)
                } else {
                    val newUser = mutableMapOf<String, Any>(
                        "email" to (auth.currentUser?.email ?: ""),
                        "lastActive" to System.currentTimeMillis()
                    )
                    if (CryptoManager.hasCloudMasterKey()) {
                        newUser["encryptedGroupIds"] = listOf(CryptoManager.encrypt(gid))
                    } else {
                        newUser["groupIds"] = listOf(gid)
                    }
                    transaction.set(userDocRef, newUser)
                }
            }.addOnSuccessListener {
                Log.d(TAG, "Group created successfully: $gid")
                loadGroupInfo()
            }.addOnFailureListener { e ->
                Log.e(TAG, "Failed to create group transaction", e)
                _state.postValue(GroupState.Error("Failed to create group: ${e.message}"))
            }
        }
    }

    fun joinGroup(id: String, pin: String?) {
        Log.d(TAG, "Attempting to join group: $id")
        viewModelScope.launch {
            val userId = auth.currentUser?.uid ?: run {
                Log.e(TAG, "Cannot join group: User not logged in")
                return@launch
            }
            try {
                Log.d(TAG, "Fetching group doc for join: $id")
                val groupDoc = db.collection("groups").document(id).get().await()
                if (!groupDoc.exists()) {
                    Log.d(TAG, "Join failed: Group not found")
                    _state.postValue(GroupState.Error("Group not found."))
                    return@launch
                }

                val group = groupDoc.toObject(Group::class.java)
                if (group?.isPrivate == true && group.pin != pin) {
                    Log.d(TAG, "Join failed: Incorrect PIN")
                    _state.postValue(GroupState.Error("Incorrect PIN."))
                    return@launch
                }

                Log.d(TAG, "Adding member to group and updating user doc")
                db.collection("groups").document(id).collection("members").document(userId).set(mapOf("uid" to userId)).await()
                
                val userUpdate = mutableMapOf<String, Any>()
                if (CryptoManager.hasCloudMasterKey()) {
                    userUpdate["encryptedGroupIds"] = FieldValue.arrayUnion(CryptoManager.encrypt(id))
                    userUpdate["groupIds"] = FieldValue.delete() // Wipe legacy plain field
                } else {
                    userUpdate["groupIds"] = FieldValue.arrayUnion(id)
                }

                db.collection("users").document(userId).set(userUpdate, SetOptions.merge()).await()
                Log.d(TAG, "Joined group successfully: $id")
                loadGroupInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join group", e)
                _state.postValue(GroupState.Error("Failed to join group: ${e.message}"))
            }
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            val userId = auth.currentUser?.uid ?: return@launch
            try {
                db.collection("groups").document(groupId).collection("members").document(userId).delete().await()
                
                val userDoc = db.collection("users").document(userId).get().await()
                if (userDoc.exists()) {
                    val userUpdate = mutableMapOf<String, Any>()
                    userUpdate["groupIds"] = FieldValue.arrayRemove(groupId)
                    
                    val encryptedGroupIds = (userDoc.get("encryptedGroupIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    if (CryptoManager.hasCloudMasterKey() && encryptedGroupIds.isNotEmpty()) {
                        for (encId in encryptedGroupIds) {
                            try {
                                if (CryptoManager.decrypt(encId) == groupId) {
                                    userUpdate["encryptedGroupIds"] = FieldValue.arrayRemove(encId)
                                    break
                                }
                            } catch (e: Exception) { /* Ignore decrypt failure */ }
                        }
                    }
                    db.collection("users").document(userId).update(userUpdate).await()
                }
                
                loadGroupInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to leave group", e)
                _state.postValue(GroupState.Error("Failed to leave group."))
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            val userId = auth.currentUser?.uid ?: return@launch
            try {
                val groupDoc = db.collection("groups").document(groupId).get().await()
                val group = groupDoc.toObject(Group::class.java)
                
                if (group?.ownerId != userId) {
                    _state.postValue(GroupState.Error("Only the owner can delete this group."))
                    return@launch
                }

                db.collection("groups").document(groupId).delete().await()
                
                val userDoc = db.collection("users").document(userId).get().await()
                if (userDoc.exists()) {
                    val userUpdate = mutableMapOf<String, Any>()
                    userUpdate["groupIds"] = FieldValue.arrayRemove(groupId)
                    
                    val encryptedGroupIds = (userDoc.get("encryptedGroupIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    if (CryptoManager.hasCloudMasterKey() && encryptedGroupIds.isNotEmpty()) {
                        for (encId in encryptedGroupIds) {
                            try {
                                if (CryptoManager.decrypt(encId) == groupId) {
                                    userUpdate["encryptedGroupIds"] = FieldValue.arrayRemove(encId)
                                    break
                                }
                            } catch (e: Exception) { /* Ignore decrypt failure */ }
                        }
                    }
                    db.collection("users").document(userId).update(userUpdate).await()
                }
                
                loadGroupInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete group", e)
                _state.postValue(GroupState.Error("Failed to delete group."))
            }
        }
    }
}
