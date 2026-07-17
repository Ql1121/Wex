package wex.ui.core.core

import android.view.View
import android.view.ViewGroup

/**
 * 通用视图查找工具（跨版本、不依赖资源 id / 类名 / DexKit）。
 * 核心思路：靠 contentDescription（无障碍描述文字）匹配，微信一般不改这些文字，跨版本稳定。
 */
object ViewFinder {

    /**
     * BFS 遍历视图树，按 contentDescription 关键词匹配控件。
     * @param root 根视图
     * @param descKeyword 无障碍描述关键词（包含匹配）
     * @param type 可选，限定控件类型；null 表示不限
     */
    fun findViewByDesc(root: View?, descKeyword: String, type: Class<*>? = null): View? {
        if (root == null) return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cd = v.contentDescription
            if (cd != null && cd.toString().contains(descKeyword)) {
                if (type == null || type.isInstance(v)) return v
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    /**
     * BFS 查找第一个指定类型的控件（按类名包含匹配，兼容混淆子类）。
     * @param classNameContains 类名包含的关键词，如 "ListView"、"RecyclerView"
     */
    fun findViewByClassName(root: View?, classNameContains: String): View? {
        if (root == null) return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.name.contains(classNameContains)) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    /** 递归找第一个 ListView（聊天列表兜底，不依赖资源 id） */
    fun findListView(root: View?): View? = findViewByClassName(root, "ListView")
}