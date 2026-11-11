/* Build only with an audited static toolchain. No shell, PATH lookup or caller args. */
#include <unistd.h>
#include <stdio.h>

int main(int argc, char **argv) {
    (void)argv;
    if (argc != 1) return 64;
    char *const args[] = {
        "/opt/java/openjdk/bin/java", "-Xms16m", "-Xmx256m",
        "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-Duser.home=/tmp",
        "-Djava.io.tmpdir=/tmp", "-Dtruffle.UseFallbackRuntime=true",
        "-Dpolyglot.engine.WarnInterpreterOnly=false",
        "-Dpolyglot.engine.resourcePath=/opt/code-worker/resources",
        "-cp", "/opt/code-worker/lib/*",
        "com.easyshell.codeworker.IsolationWorker", NULL
    };
    char *const empty_env[] = { NULL };
    execve(args[0], args, empty_env);
    fputs("LAUNCH_FAILED\n", stderr);
    return 126;
}
