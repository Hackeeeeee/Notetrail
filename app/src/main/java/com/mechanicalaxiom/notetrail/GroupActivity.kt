package com.mechanicalaxiom.notetrail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mechanicalaxiom.notetrail.databinding.ActivityGroupBinding
import com.mechanicalaxiom.notetrail.databinding.GroupItemBinding
import com.mechanicalaxiom.notetrail.databinding.LeaderboardItemBinding
import com.mechanicalaxiom.notetrail.databinding.ItemSubjectComparisonBinding
import com.mechanicalaxiom.notetrail.databinding.ItemPaperComparisonBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

class GroupActivity : AppCompatActivity() {

    private val viewModel: GroupViewModel by viewModels()
    private lateinit var binding: ActivityGroupBinding
    private lateinit var groupAdapter: GroupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        groupAdapter = GroupAdapter(viewModel) { groupId ->
            showComparisonDialog(groupId)
        }
        binding.groupsRecycler.layoutManager = LinearLayoutManager(this)
        binding.groupsRecycler.adapter = groupAdapter

        viewModel.state.observe(this) { state ->
            binding.groupProgressBar.visibility = if (state is GroupState.Loading) View.VISIBLE else View.GONE
            when (state) {
                is GroupState.InGroups -> {
                    binding.noGroupView.visibility = View.GONE
                    binding.groupsRecycler.visibility = View.VISIBLE
                    groupAdapter.submitList(state.groups)
                }
                is GroupState.NoGroup -> {
                    binding.noGroupView.visibility = View.VISIBLE
                    binding.groupsRecycler.visibility = View.GONE
                }
                is GroupState.Error -> Toast.makeText(this, state.message, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }

        binding.fabAddGroup.setOnClickListener {
            showAddGroupDialog()
        }

        binding.emptyJoinButton.setOnClickListener {
            showJoinGroupDialog()
        }
    }

    private fun showComparisonDialog(groupId: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_subject_comparison, null)
        val recycler = view.findViewById<RecyclerView>(R.id.comparisonRecycler)
        
        val dialog = MaterialAlertDialogBuilder(this, R.style.GlassDialogTheme)
            .setView(view)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SubjectComparisonAdapter()
        recycler.adapter = adapter

        lifecycleScope.launch {
            val comparisonData = viewModel.getSubjectComparison(groupId)
            adapter.submitList(comparisonData)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun showAddGroupDialog() {
        val options = arrayOf(getString(R.string.join_group), getString(R.string.create_group))
        val dialog = MaterialAlertDialogBuilder(this, R.style.GlassDialogTheme)
            .setTitle(R.string.add_group)
            .setItems(options) { _, which ->
                if (which == 0) showJoinGroupDialog() else showCreateGroupDialog()
            }
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun showCreateGroupDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_create_group, null)
        val nameInput = view.findViewById<EditText>(R.id.groupNameInput)
        val pinInput = view.findViewById<EditText>(R.id.groupPinInput)
        val privateCheck = view.findViewById<CheckBox>(R.id.privateCheckbox)

        val dialog = MaterialAlertDialogBuilder(this, R.style.GlassDialogTheme)
            .setTitle(R.string.create_new_group)
            .setView(view)
            .setPositiveButton(R.string.btn_create) { _, _ ->
                val name = nameInput.text.toString()
                val pin = pinInput.text.toString()
                val private = privateCheck.isChecked
                if (name.isNotEmpty()) {
                    viewModel.createGroup(name, if (private) pin else null, private)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    private fun showJoinGroupDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_join_group, null)
        val idInput = view.findViewById<EditText>(R.id.groupIdInput)
        val pinInput = view.findViewById<EditText>(R.id.groupPinInput)

        val dialog = MaterialAlertDialogBuilder(this, R.style.GlassDialogTheme)
            .setTitle(R.string.join_group)
            .setView(view)
            .setPositiveButton(R.string.btn_join) { _, _ ->
                val id = idInput.text.toString()
                val pin = pinInput.text.toString()
                if (id.isNotEmpty()) {
                    viewModel.joinGroup(id, pin)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class GroupAdapter(
    private val viewModel: GroupViewModel,
    private val onCompareClick: (String) -> Unit
) : ListAdapter<Group, GroupAdapter.ViewHolder>(GroupDiffCallback()) {

    class ViewHolder(val binding: GroupItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(GroupItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = getItem(position)
        val context = holder.itemView.context
        
        holder.binding.groupName.text = group.name
        holder.binding.groupIdText.text = context.getString(R.string.group_id_format, group.id)

        // Daily Goal Logic
        val totalSolvedToday = group.leaderboard.sumOf { it.papersSolvedToday }
        holder.binding.goalTitle.text = "Group Goal: $totalSolvedToday / ${group.dailyGoal} papers today"
        holder.binding.goalProgress.max = group.dailyGoal
        holder.binding.goalProgress.progress = totalSolvedToday

        // Leaderboard Recycler
        val adapter = LeaderboardAdapter()
        holder.binding.leaderboardRecycler.layoutManager = LinearLayoutManager(context)
        holder.binding.leaderboardRecycler.adapter = adapter
        adapter.submitList(group.leaderboard)

        // Subject Filters
        setupSubjectFilters(holder, group)

        holder.binding.leaveGroupButton.setOnClickListener {
            viewModel.leaveGroup(group.id)
        }
        
        holder.binding.copyGroupIdButton.setOnClickListener {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("ID", group.id))
            Toast.makeText(context, R.string.toast_copied_id, Toast.LENGTH_SHORT).show()
        }

        holder.binding.btnCompareAll.setOnClickListener {
            onCompareClick(group.id)
        }

        holder.binding.btnInvite.setOnClickListener {
            val inviteMsg = "Join my IGCSE study group on NoteTrail! ID: ${group.id}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, inviteMsg)
            }
            context.startActivity(Intent.createChooser(intent, "Invite via"))
        }
    }

    private fun setupSubjectFilters(holder: ViewHolder, group: Group) {
        val chipGroup = holder.binding.subjectFilterGroup
        if (chipGroup.childCount > 0) return // Already populated

        val subjects = listOf("All", "0610", "0625", "0620", "0580", "0478", "0417")
        subjects.forEach { sub ->
            val chip = Chip(holder.itemView.context).apply {
                text = if (sub == "All") sub else "$sub"
                isCheckable = true
                isChecked = (sub == "All")
                
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        viewModel.loadGroupInfo(if (sub == "All") null else sub)
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }
}

class LeaderboardAdapter : ListAdapter<LeaderboardEntry, LeaderboardAdapter.ViewHolder>(LeaderboardDiffCallback()) {
    class ViewHolder(val binding: LeaderboardItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LeaderboardItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val context = holder.itemView.context
        
        holder.binding.rankText.text = when(entry.rank) {
            1 -> "🥇"
            2 -> "🥈"
            3 -> "🥉"
            else -> "#${entry.rank}"
        }
        holder.binding.userName.text = if (entry.isCurrentUser) {
            context.getString(R.string.user_name_you_format, entry.userName)
        } else {
            entry.userName
        }
        holder.binding.averageScore.text = String.format(Locale.US, context.getString(R.string.avg_score_format), entry.averageScore)
        holder.binding.papersSolved.text = context.getString(R.string.papers_solved_format, entry.papersSolved)

        // Who's Online Indicator (5 mins threshold)
        val isOnline = System.currentTimeMillis() - entry.lastActive < 300000
        holder.binding.onlineDot.visibility = if (isOnline) View.VISIBLE else View.GONE

        val highlightColor = context.getColor(R.color.glass_surface)
        holder.binding.leaderboardConstraint.setBackgroundColor(
            if (entry.isCurrentUser) highlightColor else Color.TRANSPARENT
        )

        holder.itemView.setOnLongClickListener {
            showReportDialog(context, entry.userName)
            true
        }
    }

    private fun showReportDialog(context: Context, userName: String) {
        val dialog = MaterialAlertDialogBuilder(context, R.style.GlassDialogTheme)
            .setTitle("Report User")
            .setMessage("Do you want to report $userName for inappropriate behavior?")
            .setPositiveButton("Report") { _, _ ->
                Toast.makeText(context, "User reported. We will review this shortly.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.glass_dialog_bg)
    }
}

class GroupDiffCallback : DiffUtil.ItemCallback<Group>() {
    override fun areItemsTheSame(old: Group, new: Group) = old.id == new.id
    override fun areContentsTheSame(old: Group, new: Group) = old == new
}

class LeaderboardDiffCallback : DiffUtil.ItemCallback<LeaderboardEntry>() {
    override fun areItemsTheSame(old: LeaderboardEntry, new: LeaderboardEntry) = old.userId == new.userId
    override fun areContentsTheSame(old: LeaderboardEntry, new: LeaderboardEntry) = old == new
}

class SubjectComparisonAdapter : ListAdapter<SubjectComparison, SubjectComparisonAdapter.ViewHolder>(SubjectComparisonDiffCallback()) {
    class ViewHolder(val binding: ItemSubjectComparisonBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSubjectComparisonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.binding.subjectCode.text = entry.subjectCode
        
        holder.binding.groupProgress.progress = entry.groupAverage.toInt()
        holder.binding.groupAvgText.text = String.format(Locale.US, "%.1f%%", entry.groupAverage)
        
        holder.binding.userProgress.progress = entry.userAverage.toInt()
        holder.binding.userAvgText.text = String.format(Locale.US, "%.1f%%", entry.userAverage)

        // Clear and populate paper-level breakdown
        holder.binding.papersContainer.removeAllViews()
        entry.papers.forEach { paper ->
            val paperBinding = ItemPaperComparisonBinding.inflate(LayoutInflater.from(holder.itemView.context), holder.binding.papersContainer, false)
            paperBinding.paperName.text = paper.paperName
            paperBinding.groupProgress.progress = paper.groupAverage.toInt()
            paperBinding.groupAvgText.text = String.format(Locale.US, "%.1f%%", paper.groupAverage)
            
            paperBinding.userProgress.progress = paper.userAverage.toInt()
            paperBinding.userAvgText.text = if (paper.userAverage > 0) String.format(Locale.US, "%.1f%%", paper.userAverage) else "--%"
            
            holder.binding.papersContainer.addView(paperBinding.root)
        }

        holder.itemView.setOnClickListener {
            val isVisible = holder.binding.papersContainer.visibility == View.VISIBLE
            holder.binding.papersContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        }
    }
}

class SubjectComparisonDiffCallback : DiffUtil.ItemCallback<SubjectComparison>() {
    override fun areItemsTheSame(old: SubjectComparison, new: SubjectComparison) = old.subjectCode == new.subjectCode
    override fun areContentsTheSame(old: SubjectComparison, new: SubjectComparison) = old == new
}
