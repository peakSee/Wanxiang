package top.wanxiang.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.wanxiang.app.feature.components.R

enum class RuntimeIconName {
    Home, Workspace, Terminal, Settings, Back, ChevronRight, ChevronDown, ChevronUp, Package,
    NavDashboard, NavMessage, NavRepository, NavSettings,
    Refresh, Shield, Storage, Globe, Trash, Close, Check, Alert, Logs,
    Download, Play, Stop, More, Plus, Chat, List, Copy,
    Folder, File, Code, Edit, Save, ArrowUp, Cpu, Search, Info,
    Image, Attach,
    // 官方精准品牌与系统/框架 Logo
    Linux, Debian, Ubuntu, Arch, Kali, Fedora, Alpine, Void,
    Android, Flutter,
    Github, Qq, GitBranch,
    Bot, Palette, FontSize, Battery, Bug, Update, Extension, Hub, Mount, OpenInNew, Key, Tune,
    Brain, Sparkles, Vibrate, FolderDownload, Document, SdCard, Server, Compress,
    Prompt, Wrench, Model, Network, Community, FolderOpen, Speed, Cable, Admin, Link,
    Reverse, PowerSettingsNew, Visibility, VisibilityOff, Sponsor, Mail,
}

/**
 * 统一图标入口：全部走 res/drawable 单轨渲染（Material Symbols Outlined + Lucide + 品牌 Logo），
 * 单色图标默认跟随内容色 tint，彩色品牌 Logo 保持原色。
 */
@Composable
fun RuntimeIcon(
    name: RuntimeIconName,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val resId = when (name) {
        RuntimeIconName.Home -> R.drawable.components_ic_home
        RuntimeIconName.Workspace -> R.drawable.components_ic_workspace
        RuntimeIconName.Terminal -> R.drawable.components_ic_terminal
        RuntimeIconName.Settings -> R.drawable.components_ic_settings
        RuntimeIconName.Back -> R.drawable.components_ic_back
        RuntimeIconName.ChevronRight -> R.drawable.components_ic_chevronright
        RuntimeIconName.ChevronDown -> R.drawable.components_ic_chevrondown
        RuntimeIconName.ChevronUp -> R.drawable.components_ic_chevronup
        RuntimeIconName.Package -> R.drawable.components_ic_package
        RuntimeIconName.NavDashboard -> R.drawable.components_ic_nav_dashboard
        RuntimeIconName.NavMessage -> R.drawable.components_ic_nav_message
        RuntimeIconName.NavRepository -> R.drawable.components_ic_nav_repository
        RuntimeIconName.NavSettings -> R.drawable.components_ic_nav_settings
        RuntimeIconName.Refresh -> R.drawable.components_ic_refresh
        RuntimeIconName.Shield -> R.drawable.components_ic_shield
        RuntimeIconName.Storage -> R.drawable.components_ic_storage
        RuntimeIconName.Globe -> R.drawable.components_ic_globe
        RuntimeIconName.Trash -> R.drawable.components_ic_trash
        RuntimeIconName.Close -> R.drawable.components_ic_close
        RuntimeIconName.Check -> R.drawable.components_ic_check
        RuntimeIconName.Alert -> R.drawable.components_ic_alert
        RuntimeIconName.Logs -> R.drawable.components_ic_logs
        RuntimeIconName.Download -> R.drawable.components_ic_download
        RuntimeIconName.Play -> R.drawable.components_ic_play
        RuntimeIconName.Stop -> R.drawable.components_ic_stop
        RuntimeIconName.More -> R.drawable.components_ic_more
        RuntimeIconName.Plus -> R.drawable.components_ic_plus
        RuntimeIconName.Chat -> R.drawable.components_ic_chat
        RuntimeIconName.List -> R.drawable.components_ic_list
        RuntimeIconName.Copy -> R.drawable.components_ic_copy
        RuntimeIconName.Folder -> R.drawable.components_ic_folder
        RuntimeIconName.File -> R.drawable.components_ic_file
        RuntimeIconName.Code -> R.drawable.components_ic_code
        RuntimeIconName.Edit -> R.drawable.components_ic_edit
        RuntimeIconName.Save -> R.drawable.components_ic_save
        RuntimeIconName.ArrowUp -> R.drawable.components_ic_arrowup
        RuntimeIconName.Cpu -> R.drawable.components_ic_cpu
        RuntimeIconName.Search -> R.drawable.components_ic_search
        RuntimeIconName.Info -> R.drawable.components_ic_info
        RuntimeIconName.Image -> R.drawable.components_ic_image
        RuntimeIconName.Attach -> R.drawable.components_ic_attach
        RuntimeIconName.Linux -> R.drawable.components_ic_logo_linux
        RuntimeIconName.Debian -> R.drawable.components_ic_logo_debian
        RuntimeIconName.Ubuntu -> R.drawable.components_ic_logo_ubuntu
        RuntimeIconName.Arch -> R.drawable.components_ic_logo_arch
        RuntimeIconName.Kali -> R.drawable.components_ic_logo_kali
        RuntimeIconName.Fedora -> R.drawable.components_ic_logo_fedora
        RuntimeIconName.Alpine -> R.drawable.components_ic_logo_alpine
        RuntimeIconName.Void -> R.drawable.components_ic_logo_void
        RuntimeIconName.Android -> R.drawable.components_ic_logo_android
        RuntimeIconName.Flutter -> R.drawable.components_ic_logo_flutter
        RuntimeIconName.Github -> R.drawable.components_ic_github
        RuntimeIconName.GitBranch -> R.drawable.components_ic_gitbranch
        RuntimeIconName.Qq -> R.drawable.components_ic_qq
        RuntimeIconName.Bot -> R.drawable.components_ic_bot
        RuntimeIconName.Palette -> R.drawable.components_ic_palette
        RuntimeIconName.FontSize -> R.drawable.components_ic_fontsize
        RuntimeIconName.Battery -> R.drawable.components_ic_battery
        RuntimeIconName.Bug -> R.drawable.components_ic_bug
        RuntimeIconName.Update -> R.drawable.components_ic_update
        RuntimeIconName.Extension -> R.drawable.components_ic_extension
        RuntimeIconName.Hub -> R.drawable.components_ic_hub
        RuntimeIconName.Mount -> R.drawable.components_ic_mount
        RuntimeIconName.OpenInNew -> R.drawable.components_ic_openinnew
        RuntimeIconName.Key -> R.drawable.components_ic_key
        RuntimeIconName.Tune -> R.drawable.components_ic_tune
        RuntimeIconName.Brain -> R.drawable.components_ic_brain
        RuntimeIconName.Sparkles -> R.drawable.components_ic_sparkles
        RuntimeIconName.Vibrate -> R.drawable.components_ic_vibrate
        RuntimeIconName.FolderDownload -> R.drawable.components_ic_folderdownload
        RuntimeIconName.Document -> R.drawable.components_ic_document
        RuntimeIconName.SdCard -> R.drawable.components_ic_sdcard
        RuntimeIconName.Server -> R.drawable.components_ic_server
        RuntimeIconName.Compress -> R.drawable.components_ic_compress
        RuntimeIconName.Prompt -> R.drawable.components_ic_prompt
        RuntimeIconName.Wrench -> R.drawable.components_ic_wrench
        RuntimeIconName.Model -> R.drawable.components_ic_model
        RuntimeIconName.Network -> R.drawable.components_ic_network
        RuntimeIconName.Community -> R.drawable.components_ic_community
        RuntimeIconName.FolderOpen -> R.drawable.components_ic_folderopen
        RuntimeIconName.Speed -> R.drawable.components_ic_speed
        RuntimeIconName.Cable -> R.drawable.components_ic_cable
        RuntimeIconName.Admin -> R.drawable.components_ic_admin
        RuntimeIconName.Link -> R.drawable.components_ic_link
        RuntimeIconName.Reverse -> R.drawable.components_ic_reverse
        RuntimeIconName.PowerSettingsNew -> R.drawable.components_ic_powersettingsnew
        RuntimeIconName.Visibility -> R.drawable.components_ic_visibility
        RuntimeIconName.VisibilityOff -> R.drawable.components_ic_visibilityoff
        RuntimeIconName.Sponsor -> R.drawable.components_ic_sponsor
        RuntimeIconName.Mail -> R.drawable.components_ic_mail
    }

    // 彩色品牌 Logo 保持原色；单色图标跟随内容色，可被显式 tint 覆盖
    val isColorfulBrand = name in COLORFUL_BRAND_ICONS
    val effectiveTint = when {
        isColorfulBrand && tint == Color.Unspecified -> Color.Unspecified
        tint != Color.Unspecified -> tint
        else -> LocalContentColor.current
    }
    Icon(
        painter = androidx.compose.ui.res.painterResource(resId),
        contentDescription = null,
        modifier = modifier,
        tint = effectiveTint,
    )
}

/** 多色品牌 Logo 集合：不参与 tint，展示资源原始配色 */
private val COLORFUL_BRAND_ICONS = setOf(
    RuntimeIconName.Debian,
    RuntimeIconName.Ubuntu,
    RuntimeIconName.Arch,
    RuntimeIconName.Kali,
    RuntimeIconName.Fedora,
    RuntimeIconName.Alpine,
    RuntimeIconName.Void,
    RuntimeIconName.Android,
    RuntimeIconName.Flutter,
)

/**
 * 根据发行版标识返回专有官方 Linux 发行版 Logo
 */
fun distroIconFor(distroId: String): RuntimeIconName = when (distroId.lowercase()) {
    "debian" -> RuntimeIconName.Debian
    "ubuntu" -> RuntimeIconName.Ubuntu
    "arch", "archlinux" -> RuntimeIconName.Arch
    "kali" -> RuntimeIconName.Kali
    "fedora" -> RuntimeIconName.Fedora
    "alpine" -> RuntimeIconName.Alpine
    "void" -> RuntimeIconName.Void
    else -> RuntimeIconName.Linux
}
