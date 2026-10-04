package com.kksg.applicationServices.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduler.
 *
 * <p>Its own class rather than an annotation on the application class, for two reasons. It documents
 * what scheduling is for here - at present exactly one job, the SCM token refresh sweep - and it is a
 * seam a test slice can exclude, which an annotation on {@code @SpringBootApplication} is not.
 *
 * <p><b>Single-instance assumption.</b> The default scheduler is in-process, so every replica runs
 * every job. For the token sweep that is tolerable rather than correct: the work it does is guarded by
 * a {@code SELECT ... FOR UPDATE} on each connection and re-checked after the lock, so two replicas
 * sweeping at once produce one refresh and one no-op re-read rather than two exchanges of the same
 * refresh token. What it does waste is a duplicated query per replica.
 *
 * <p>If a job is ever added whose work is <i>not</i> idempotent under concurrency, it needs a shared
 * lock (a database advisory lock, or ShedLock) before this becomes safe at more than one replica.
 * Recorded here because the default looks harmless and is the kind of assumption that only surfaces
 * when a deployment is scaled out.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
