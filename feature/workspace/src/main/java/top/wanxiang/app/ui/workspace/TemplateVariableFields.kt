package top.wanxiang.app.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import top.wanxiang.app.feature.workspace.R
import top.wanxiang.app.template.ProjectTemplateInputType
import top.wanxiang.app.template.ProjectTemplateVariable

@Composable
private fun VariableLabel(label: String, required: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (required) {
            Text(" *", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun TemplateVariableFields(
    variables: List<ProjectTemplateVariable>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
) {
    variables.filter { it.prompt }.forEach { variable ->
        val value = values[variable.name] ?: variable.defaultValue
        val error = templateVariableError(variable, value)
        when (variable.inputType) {
            ProjectTemplateInputType.BOOLEAN -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = value.equals("true", ignoreCase = true),
                    onCheckedChange = { onValueChange(variable.name, it.toString()) },
                )
                Column(Modifier.padding(start = 8.dp)) {
                    VariableLabel(variable.label, variable.required)
                    if (variable.description.isNotBlank()) {
                        Text(variable.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            ProjectTemplateInputType.SELECT -> {
                var expanded by remember(variable.name) { mutableStateOf(false) }
                Column {
                    OutlinedTextField(
                        value = variable.options.firstOrNull { it.value == value }?.label ?: value,
                        onValueChange = {},
                        readOnly = true,
                        label = { VariableLabel(variable.label, variable.required) },
                        supportingText = variable.description.takeIf(String::isNotBlank)?.let { description ->
                            { Text(description) }
                        },
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) { Text("▾") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        variable.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onValueChange(variable.name, option.value)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            else -> OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(variable.name, it) },
                label = { VariableLabel(variable.label, variable.required) },
                placeholder = variable.placeholder.takeIf(String::isNotBlank)?.let { { Text(it) } },
                supportingText = {
                    Text(error ?: variable.description)
                },
                isError = error != null,
                singleLine = variable.inputType != ProjectTemplateInputType.MULTILINE,
                minLines = if (variable.inputType == ProjectTemplateInputType.MULTILINE) 3 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (variable.inputType == ProjectTemplateInputType.NUMBER) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Text
                    },
                ),
                visualTransformation = if (variable.inputType == ProjectTemplateInputType.SECRET) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 校验失败类型：REQUIRED = 必填为空；INVALID_FORMAT = 不满足 validationRegex。 */
internal enum class TemplateVariableErrorKind { REQUIRED, INVALID_FORMAT }

internal fun templateVariableErrorKind(variable: ProjectTemplateVariable, value: String): TemplateVariableErrorKind? {
    if (variable.required && value.isBlank()) return TemplateVariableErrorKind.REQUIRED
    if (value.isNotBlank() && variable.validationRegex.isNotBlank() &&
        !runCatching { Regex(variable.validationRegex).matches(value) }.getOrDefault(false)
    ) {
        return TemplateVariableErrorKind.INVALID_FORMAT
    }
    return null
}

/** 校验失败的本地化文案（必须在 Composable 上下文中调用）。 */
@Composable
internal fun templateVariableError(variable: ProjectTemplateVariable, value: String): String? =
    when (templateVariableErrorKind(variable, value)) {
        null -> null
        TemplateVariableErrorKind.REQUIRED -> stringResource(R.string.template_variable_required, variable.label)
        TemplateVariableErrorKind.INVALID_FORMAT -> stringResource(R.string.template_variable_invalid_format, variable.label)
    }
