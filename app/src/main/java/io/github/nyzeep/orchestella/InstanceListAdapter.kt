package io.github.nyzeep.orchestella

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * 实例列表 RecyclerView 适配器（UI 重新设计轮 C6 逻辑段）。
 *
 * 纯结构壳：分组 header（运行中/已停止）与实例卡两种 view type 的扁平
 * 行序列；卡片的数据绑定、监听、呼吸动画与主题重涂仍由 MainActivity
 * 通过 [cardBinder] 提供——绑定代码原位保留在渲染方（热路径回滚粒度
 * 细），适配器不持有业务语义。
 *
 * 全量 notifyDataSetChanged：与原 LinearLayout 全量重渲染语义一致
 * （完整重渲染频率已由 DashboardSnapshot 状态指纹门控）；条目进场动效
 * 不用 layoutAnimation（RecyclerView 子项增删会重触发），仍走程序化
 * OrchestellaMotion.playListEntryStagger（含 5 步交错上限）。
 */
class InstanceListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 列表行：分组 header 或实例卡。 */
    sealed class Row {
        data class Section(val title: String) : Row()
        data class Card(val instance: InstanceLifecycle.Instance, val uiModel: InstanceCardUiModel) : Row()
    }

    private val rows = mutableListOf<Row>()

    /** 卡片绑定回调：MainActivity 提供（renderInstances 每次 full render 刷新）。 */
    var cardBinder: ((cardView: View, row: Row.Card) -> Unit)? = null

    fun submit(rows: List<Row>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Section -> VIEW_TYPE_SECTION
        is Row.Card -> VIEW_TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionViewHolder(
                inflater.inflate(R.layout.layout_instance_section_header, parent, false)
            )
            else -> CardViewHolder(
                inflater.inflate(R.layout.layout_instance_card, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionViewHolder).title.text = row.title
            is Row.Card -> cardBinder?.invoke((holder as CardViewHolder).cardView, row)
        }
    }

    class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.instance_section_title)
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: View = view
    }

    private companion object {
        const val VIEW_TYPE_SECTION = 0
        const val VIEW_TYPE_CARD = 1
    }
}
