package ai.mishell.app.codex

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.mishell.app.databinding.ItemCodexProjectBinding

class ProjectListAdapter(
    private val onClick: (CodexProject) -> Unit
) : RecyclerView.Adapter<ProjectListAdapter.ProjectViewHolder>() {
    private val items = mutableListOf<CodexProject>()

    fun submitList(projects: List<CodexProject>) {
        items.clear()
        items.addAll(projects)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding = ItemCodexProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    class ProjectViewHolder(
        private val binding: ItemCodexProjectBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(project: CodexProject, onClick: (CodexProject) -> Unit) {
            binding.nameText.text = project.name
            binding.pathText.text = project.codexCwd
            binding.permissionsChip.text = when (project.permissionsPreset) {
                CodexPermissionsPreset.WORKSPACE_WRITE -> "workspace-write"
                CodexPermissionsPreset.DANGER_FULL_ACCESS -> "danger-full-access"
            }
            binding.root.setOnClickListener { onClick(project) }
        }
    }
}
