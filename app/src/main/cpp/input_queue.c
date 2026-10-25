#include "cs_common.h"
#include <pthread.h>
#include <time.h>
#include <errno.h>

#define CAP 4096
static cs_event ring[CAP];
static int head = 0, tail = 0, count = 0;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t cv = PTHREAD_COND_INITIALIZER;
static int wake_flag = 0;

void cs_queue_push(const cs_event *e) {
    pthread_mutex_lock(&mu);
    if (count == CAP) {
        /* Drop the oldest cursor movement first; never drop key/button releases silently. */
        int dropped = 0;
        for (int i = 0, idx = head; i < count; i++, idx = (idx + 1) % CAP) {
            if (ring[idx].type == CS_EV_CURSOR_POS) { ring[idx] = ring[head]; head = (head + 1) % CAP; count--; dropped = 1; break; }
        }
        if (!dropped) { head = (head + 1) % CAP; count--; }
    }
    ring[tail] = *e; tail = (tail + 1) % CAP; count++;
    pthread_cond_signal(&cv);
    pthread_mutex_unlock(&mu);
}

int cs_queue_pop(cs_event *out) {
    pthread_mutex_lock(&mu);
    int ok = 0;
    if (count > 0) { *out = ring[head]; head = (head + 1) % CAP; count--; ok = 1; }
    pthread_mutex_unlock(&mu);
    return ok;
}

int cs_queue_wait(double timeout_s) {
    pthread_mutex_lock(&mu);
    if (count == 0 && !wake_flag) {
        if (timeout_s < 0) pthread_cond_wait(&cv, &mu);
        else {
            struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
            long ns = (long)((timeout_s - (long)timeout_s) * 1e9);
            ts.tv_sec += (time_t)timeout_s + (ts.tv_nsec + ns) / 1000000000L;
            ts.tv_nsec = (ts.tv_nsec + ns) % 1000000000L;
            pthread_cond_timedwait(&cv, &mu, &ts);
        }
    }
    wake_flag = 0;
    int r = count > 0;
    pthread_mutex_unlock(&mu);
    return r;
}

void cs_queue_wake(void) { pthread_mutex_lock(&mu); wake_flag = 1; pthread_cond_broadcast(&cv); pthread_mutex_unlock(&mu); }
void cs_queue_clear(void) { pthread_mutex_lock(&mu); head = tail = count = 0; pthread_mutex_unlock(&mu); }
