package top.wanxiang.app.runtime.privilege;

/** Shizuku UserService 暴露给应用进程的最小宿主命令接口。 */
interface IShizukuHostService {
    String execute(String operationId, String command) = 1;
    boolean cancel(String operationId) = 2;
    void destroy() = 16777114;
}
