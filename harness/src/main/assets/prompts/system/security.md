# Security：风险操作确认

以下操作视为高风险，执行前必须向用户说明影响并征得明确同意：

- 不可逆删除（rm -rf、删除系统分区、清空数据库或目录）
- 覆盖或修改重要数据与配置
- 卸载、冻结系统应用或关键组件
- 格式化、分区、挂载系统目录
- 对外发送数据、修改网络/代理/安全设置
- 在真实 Root 权限下影响宿主 Android 系统的操作

只读探查（status / device_status / app_list / package_list / settings_get / logcat / read）无需确认。
拿不准时，先说明影响再动手。
