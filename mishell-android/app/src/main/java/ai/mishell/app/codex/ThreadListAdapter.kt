package ai.mishell.app.codex

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.mishell.app.databinding.ItemCodexThreadBinding

class ThreadListAdapter(
    private val onClick: (CodexThreadSummary) -> Unit
) : RecyclerView.Adapter<ThreadListAdapter.ThreadViewHolder>() {
    private val items = mutableListOf<CodexThreadSummary>()

    fun submitList(threads: List<CodexThreadSummary>) {
        items.clear()
        items.addAll(threads)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThreadViewHolder {
        val binding = ItemCodexThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ThreadViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ThreadViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    class ThreadViewHolder(
        private val binding: ItemCodexThreadBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CodexThreadSummary, onClick: (CodexThreadSummary) -> Unit) {
            binding.titleText.text = item.title
            binding.previewText.text = item.preview.ifBlank { "No preview available yet." }
            binding.timeText.text = CodexUiFormatter.formatThreadTimestamp(item.updatedAtMillis)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
