package com.easyshell.server.model.vo;

import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.entity.Task;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailVO {

    private Task task;
    private List<Job> jobs;
}
