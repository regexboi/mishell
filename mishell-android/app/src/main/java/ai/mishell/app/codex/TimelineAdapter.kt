package ai.mishell.app.codex

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.mishell.app.R
import ai.mishell.app.databinding.ItemCodexTimelineBinding

class TimelineAdapter : RecyclerView.Adapter<TimelineAdapter.TimelineViewHolder>() {
    private val items = mutableListOf<CodexTimelineEntry>()

    fun submitList(entries: List<CodexTimelineEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
        val binding = ItemCodexTimelineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TimelineViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class TimelineViewHolder(
        private val binding: ItemCodexTimelineBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CodexTimelineEntry) {
            binding.titleText.text = item.title
            binding.bodyText.text = item.body.ifBlank { " " }
            binding.detailText.text = item.detail
            binding.detailText.visibility = if (item.detail.isNullOrBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
            binding.statusText.text = item.status
            binding.statusText.visibility = if (item.status.isNullOrBlank()) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }

            val context = binding.root.context
            val params = binding.bubble.layoutParams as ViewGroup.MarginLayoutParams
            when (item.speaker) {
                CodexTimelineSpeaker.USER -> {
                    binding.root.gravity = Gravity.END
                    binding.bubble.background = ContextCompat.getDrawable(context, R.drawable.bg_codex_user_bubble)
                    params.marginStart = context.resources.displayMetrics.density.times(64).toInt()
                    params.marginEnd = 0
                    binding.titleText.setTextColor(ContextCompat.getColor(context, R.color.cyber_cyan))
                }

                CodexTimelineSpeaker.ASSISTANT -> {
                    binding.root.gravity = Gravity.START
                    binding.bubble.background = ContextCompat.getDrawable(context, R.drawable.bg_codex_assistant_bubble)
                    params.marginStart = 0
                    params.marginEnd = context.resources.displayMetrics.density.times(64).toInt()
                    binding.titleText.setTextColor(ContextCompat.getColor(context, R.color.cyber_pink))
                }

                CodexTimelineSpeaker.SYSTEM -> {
                    binding.root.gravity = Gravity.FILL_HORIZONTAL
                    binding.bubble.background = ContextCompat.getDrawable(context, R.drawable.bg_codex_surface)
                    params.marginStart = 0
                    params.marginEnd = 0
                    binding.titleText.setTextColor(ContextCompat.getColor(context, R.color.cyber_cyan))
                }
            }
            binding.bubble.layoutParams = params
        }
    }
}
