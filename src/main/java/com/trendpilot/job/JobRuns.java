package com.trendpilot.job;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** job_run 表：程序是否成功 与 数据是否完整 分开记录。 */
@Repository
public class JobRuns {

    private final JdbcClient jdbc;

    public JobRuns(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String job, String generation, Instant started, Instant finished, String programStatus,
                       String dataStatus, String note) {
        jdbc.sql("""
                INSERT INTO job_run(job, generation, started_at, finished_at, program_status, data_status, note)
                VALUES (:job, :gen, :s, :f, :ps, :ds, :note)
                """)
                .param("job", job).param("gen", generation).param("s", started.toString())
                .param("f", finished.toString()).param("ps", programStatus).param("ds", dataStatus)
                .param("note", note).update();
    }

    /** 最近一次运行（无论结果）的开始时间。 */
    public Optional<Instant> lastStarted(String job) {
        return jdbc.sql("SELECT MAX(started_at) FROM job_run WHERE job = :job").param("job", job)
                .query(String.class).optional().map(Instant::parse);
    }
}
