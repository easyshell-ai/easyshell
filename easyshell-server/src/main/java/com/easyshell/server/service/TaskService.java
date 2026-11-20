package com.easyshell.server.service;

import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.dto.TaskCreateRequest;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import com.easyshell.server.model.vo.TaskDetailVO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface TaskService {

    Task createAndDispatch(TaskCreateRequest request, Long userId);

    /**
     * 带显式派发授权的咽喉要道重载（B1-b / C-3）。
     *
     * <p>{@code authority} 只能由服务端白名单调用方按调用路径传入（见
     * {@link DispatchAuthority}），绝不可从请求体绑定；{@code evidenceRef} 是该授权值
     * 的可验证证据引用（如审批通过的任务 id / 定时任务 id），证据不成立一律 449
     * （fail-closed），被豁免的派发同步写审计留痕。
     */
    Task createAndDispatch(TaskCreateRequest request, Long userId,
                           DispatchAuthority authority, String evidenceRef);

    TaskDetailVO getTaskDetail(String taskId);

    List<Task> listTasks();

    /** 归属过滤（B2）：只返回指定用户创建的任务。 */
    List<Task> listTasks(Long createdBy);

    Page<Task> listTasks(Integer status, Pageable pageable);

    /** 归属过滤（B2）：只返回指定用户创建的任务（分页）。 */
    Page<Task> listTasksByUser(Long createdBy, Pageable pageable);

    /** 归属过滤（B2）：只返回指定用户创建的指定状态任务（分页）。 */
    Page<Task> listTasksByUser(Integer status, Long createdBy, Pageable pageable);

    /**
     * @param agentId the agent credential subject that submitted the result; must own the job.
     */
    void reportJobResult(JobResultRequest request, String agentId);

    void appendJobLog(String jobId, String logLine, String agentId);

    List<Job> getAgentJobs(String agentId);

    void timeoutStaleJobs();

    void failRunningJobsForAgent(String agentId, String reason);
}
