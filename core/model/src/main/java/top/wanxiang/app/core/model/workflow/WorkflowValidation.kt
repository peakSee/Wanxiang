package top.wanxiang.app.core.model.workflow

data class WorkflowValidationIssue(val field: String, val message: String)

object WorkflowValidator {
    fun validate(definition: WorkflowDefinition): List<WorkflowValidationIssue> {
        val issues = mutableListOf<WorkflowValidationIssue>()
        if (definition.id.isBlank()) issues += WorkflowValidationIssue("id", "工作流 ID 不能为空")
        if (definition.name.isBlank()) issues += WorkflowValidationIssue("name", "工作流名称不能为空")
        if (definition.nodes.isEmpty()) issues += WorkflowValidationIssue("nodes", "工作流至少需要一个节点")

        val nodeIds = definition.nodes.map { it.id }
        if (nodeIds.size != nodeIds.distinct().size) {
            issues += WorkflowValidationIssue("nodes", "节点 ID 必须唯一")
        }
        definition.nodes.forEach { node ->
            if (node.id.isBlank()) issues += WorkflowValidationIssue("nodes", "节点 ID 不能为空")
            if (node.title.isBlank()) issues += WorkflowValidationIssue("nodes.${node.id}.title", "节点标题不能为空")
            if (node.timeoutSeconds !in 1..3600) {
                issues += WorkflowValidationIssue("nodes.${node.id}.timeoutSeconds", "超时必须在 1–3600 秒之间")
            }
            if (node.type == WorkflowNodeType.CONDITION_BRANCH) {
                val expression = node.config["expression"] ?: node.config["condition"]
                WorkflowConditionEvaluator.validationError(expression)?.let { message ->
                    issues += WorkflowValidationIssue("nodes.${node.id}.expression", message)
                }
            }
            if (node.type == WorkflowNodeType.HOST_ACTION) {
                val action = node.config["action"].orEmpty().ifBlank { "status" }
                val def = HostWorkflowActions.find(action)
                if (def == null && node.config["command"].isNullOrBlank()) {
                    issues += WorkflowValidationIssue(
                        "nodes.${node.id}.action",
                        "未知宿主动作：$action（可改用已知动作，或提供 command 作为逃逸舱）",
                    )
                }
                def?.fields?.filter { it.required }?.forEach { field ->
                    if (node.config[field.key].isNullOrBlank()) {
                        issues += WorkflowValidationIssue(
                            "nodes.${node.id}.${field.key}",
                            "宿主动作「${def.label}」缺少必填参数：${field.label}",
                        )
                    }
                }
            }
            if (node.type == WorkflowNodeType.DELAY) {
                val seconds = node.config["seconds"] ?: node.config["delaySeconds"]
                if (seconds != null && seconds.toDoubleOrNull() == null) {
                    issues += WorkflowValidationIssue("nodes.${node.id}.seconds", "等待秒数必须是数字")
                }
            }
        }

        val known = nodeIds.toSet()
        val edgeIds = definition.edges.map { it.id }
        if (edgeIds.size != edgeIds.distinct().size) {
            issues += WorkflowValidationIssue("edges", "连接 ID 必须唯一")
        }
        definition.edges.forEach { edge ->
            if (edge.id.isBlank()) issues += WorkflowValidationIssue("edges", "连接 ID 不能为空")
            if (edge.fromNodeId !in known || edge.toNodeId !in known) {
                issues += WorkflowValidationIssue("edges.${edge.id}", "边引用了不存在的节点")
            }
            if (edge.fromNodeId == edge.toNodeId) {
                issues += WorkflowValidationIssue("edges.${edge.id}", "节点不能连接到自身")
            }
            WorkflowEdgeCondition.validationError(edge)?.let { message ->
                issues += WorkflowValidationIssue("edges.${edge.id}.condition", message)
            }
        }

        if (known.isNotEmpty() && containsCycle(definition)) {
            issues += WorkflowValidationIssue("edges", "工作流必须是无环图（DAG）")
        }
        return issues
    }

    private fun containsCycle(definition: WorkflowDefinition): Boolean {
        val indegree = definition.nodes.associate { it.id to 0 }.toMutableMap()
        val outgoing = definition.edges.groupBy { it.fromNodeId }
        definition.edges.forEach { edge ->
            if (edge.toNodeId in indegree && edge.fromNodeId in indegree) {
                indegree[edge.toNodeId] = (indegree[edge.toNodeId] ?: 0) + 1
            }
        }
        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            outgoing[id].orEmpty().forEach { edge ->
                if (edge.toNodeId in indegree) {
                    val next = (indegree[edge.toNodeId] ?: 1) - 1
                    indegree[edge.toNodeId] = next
                    if (next == 0) ready.add(edge.toNodeId)
                }
            }
        }
        return visited != definition.nodes.size
    }
}
