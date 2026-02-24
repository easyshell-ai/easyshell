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

    TaskDetailVO getTaskDetail(String taskId);

    List<Task> listTasks();

    Page<Task> listTasks(Integer status, Pageable pageable);

    void reportJobResult(JobResultRequest request);

    void appendJobLog(String jobId, String logLine);

    List<Job> getAgentJobs(String agentId);
}
