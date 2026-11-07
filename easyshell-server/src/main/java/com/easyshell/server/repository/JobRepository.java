package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    List<Job> findByTaskId(String taskId);

    List<Job> findByAgentId(String agentId);

    List<Job> findByAgentIdAndStatus(String agentId, Integer status);

    List<Job> findByTaskIdAndStatus(String taskId, Integer status);

    long countByTaskIdAndStatus(String taskId, Integer status);

    List<Job> findByAgentIdOrderByCreatedAtDesc(String agentId);

    Page<Job> findByAgentId(String agentId, Pageable pageable);

    List<Job> findByStatus(Integer status);

    /**
     * 原子写 job 终态（status/exitCode/output/finishedAt 一把更新）。
     * <p>
     * status &lt; 2 作为竞争条件：同一 job 被并发回传两次结果时，只有第一个线程能改写
     * 该行，后到的线程按返回值判定放弃。这样 TaskServiceImpl 的计数累加可以与 job 的
     * 终态化严格一一对应，不会把同一个 job 重复计进 success/failed。
     * </p>
     *
     * @return 1 = 本线程完成了终态化；0 = job 不存在或已被其他线程置为终态
     */
    @Modifying(flushAutomatically = true)
    @Query("update Job j set j.status = :status, j.exitCode = :exitCode, j.output = :output, " +
            "j.finishedAt = :finishedAt where j.id = :id and j.status < 2")
    int finalizeJobAtomically(@Param("id") String jobId,
                              @Param("status") int status,
                              @Param("exitCode") Integer exitCode,
                              @Param("output") String output,
                              @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 原子追加一行 job 日志。
     * <p>
     * 为什么用 UPDATE 而不是"查出 output 拼接后再 save"：后者是读-改-写，
     * 同一 job 的并发日志行会互相覆盖丢失（且旧代码还没有事务边界）。
     * 这里把"读旧值 + 拼接 + 截断 + 写回"压成数据库内部的一条 UPDATE，
     * 由行锁串行化，每一行日志都能落库。
     * </p>
     * <p>
     * 长度上限：output 列是 MEDIUMTEXT（MySQL 约 16MB），拼接结果超过 maxOutputLength
     * 个字符时只保留尾部，保证永远不会把列写溢出；首行不补换行，保持旧行为。
     * RIGHT/CONCAT/COALESCE 在 MySQL 与 H2（测试库 MODE=MySQL）下均可用。
     * </p>
     *
     * @param newline            换行符（首行输出为空时不会拼接它）
     * @param maxOutputLength     output 列保留的最大字符数
     * @return 1 = 已追加；0 = job 不存在
     */
    @Modifying
    @Query("update Job j set j.output = function('right', " +
            "concat(concat(coalesce(j.output, ''), " +
            "case when j.output is null or j.output = '' then '' else :newline end), :line), " +
            ":maxOutputLength) where j.id = :id")
    int appendOutputAtomically(@Param("id") String jobId,
                               @Param("line") String logLine,
                               @Param("newline") String newline,
                               @Param("maxOutputLength") int maxOutputLength);
}
