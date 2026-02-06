#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <errno.h>
#include <android/log.h>

#define TAG "BeamLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static pid_t beam_pid = -1;
static int stdin_pipe[2] = {-1, -1};
static int stdout_pipe[2] = {-1, -1};

JNIEXPORT jint JNICALL
Java_com_example_beamapp_BeamService_nativeStartBeam(
    JNIEnv *env, jobject thiz,
    jstring beam_path, jstring home_dir, jstring boot_script)
{
    if (beam_pid > 0) {
        LOGI("BEAM already running with pid %d", beam_pid);
        return beam_pid;
    }

    const char *beam = (*env)->GetStringUTFChars(env, beam_path, NULL);
    const char *home = (*env)->GetStringUTFChars(env, home_dir, NULL);
    const char *boot = (*env)->GetStringUTFChars(env, boot_script, NULL);

    if (pipe(stdin_pipe) < 0 || pipe(stdout_pipe) < 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, beam_path, beam);
        (*env)->ReleaseStringUTFChars(env, home_dir, home);
        (*env)->ReleaseStringUTFChars(env, boot_script, boot);
        return -1;
    }

    beam_pid = fork();
    if (beam_pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, beam_path, beam);
        (*env)->ReleaseStringUTFChars(env, home_dir, home);
        (*env)->ReleaseStringUTFChars(env, boot_script, boot);
        return -1;
    }

    if (beam_pid == 0) {
        /* Child process */
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        dup2(stdin_pipe[0], STDIN_FILENO);
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stdout_pipe[1], STDERR_FILENO);
        close(stdin_pipe[0]);
        close(stdout_pipe[1]);

        /* Set up environment */
        setenv("HOME", home, 1);
        setenv("TERM", "dumb", 1);

        /* Execute beam */
        execl(beam, "beam.smp", "--", "-noshell", "-eval", boot, NULL);
        /* If we get here, exec failed */
        _exit(127);
    }

    /* Parent process */
    close(stdin_pipe[0]);
    close(stdout_pipe[1]);

    LOGI("BEAM started with pid %d", beam_pid);

    (*env)->ReleaseStringUTFChars(env, beam_path, beam);
    (*env)->ReleaseStringUTFChars(env, home_dir, home);
    (*env)->ReleaseStringUTFChars(env, boot_script, boot);

    return beam_pid;
}

JNIEXPORT void JNICALL
Java_com_example_beamapp_BeamService_nativeStopBeam(JNIEnv *env, jobject thiz)
{
    if (beam_pid > 0) {
        LOGI("Stopping BEAM pid %d", beam_pid);
        kill(beam_pid, SIGTERM);
        int status;
        waitpid(beam_pid, &status, 0);
        LOGI("BEAM exited with status %d", status);
        beam_pid = -1;
    }
    if (stdin_pipe[1] >= 0) { close(stdin_pipe[1]); stdin_pipe[1] = -1; }
    if (stdout_pipe[0] >= 0) { close(stdout_pipe[0]); stdout_pipe[0] = -1; }
}

JNIEXPORT jstring JNICALL
Java_com_example_beamapp_BeamService_nativeReadOutput(JNIEnv *env, jobject thiz)
{
    if (stdout_pipe[0] < 0) return (*env)->NewStringUTF(env, "");

    char buf[4096];
    fd_set fds;
    struct timeval tv = {0, 100000}; /* 100ms timeout */

    FD_ZERO(&fds);
    FD_SET(stdout_pipe[0], &fds);

    int ret = select(stdout_pipe[0] + 1, &fds, NULL, NULL, &tv);
    if (ret > 0) {
        ssize_t n = read(stdout_pipe[0], buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            return (*env)->NewStringUTF(env, buf);
        }
    }
    return (*env)->NewStringUTF(env, "");
}

JNIEXPORT void JNICALL
Java_com_example_beamapp_BeamService_nativeWriteInput(
    JNIEnv *env, jobject thiz, jstring input)
{
    if (stdin_pipe[1] < 0) return;

    const char *str = (*env)->GetStringUTFChars(env, input, NULL);
    write(stdin_pipe[1], str, strlen(str));
    write(stdin_pipe[1], "\n", 1);
    (*env)->ReleaseStringUTFChars(env, input, str);
}

JNIEXPORT jboolean JNICALL
Java_com_example_beamapp_BeamService_nativeIsRunning(JNIEnv *env, jobject thiz)
{
    if (beam_pid <= 0) return JNI_FALSE;
    int status;
    pid_t ret = waitpid(beam_pid, &status, WNOHANG);
    if (ret == beam_pid) {
        beam_pid = -1;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
