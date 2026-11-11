/*
 * probe_shell - 5-minute adb-shell channel feasibility probe (A-1/A-3).
 *
 * Built as a static aarch64 ELF, pushed under /data/local/tmp and exec'd by
 * the Android shell domain (uid 2000).  Reaching the PROBE lines at all is
 * the primary evidence that the shell domain can exec pushed static binaries
 * (AOSP 15 shell.te grants shell_data_file:file rx_file_perms; see
 * device/adbshell-channel.md for the quoted policy).
 *
 * It then probes the two socket capabilities the two adb-shell cases need:
 *   socketpair(AF_UNIX, SOCK_DGRAM)          -> vector-net transport=fd,fd=3
 *   bind(AF_UNIX, SOCK_DGRAM) at candidates  -> mconsole socket placement
 *
 * The mconsole caveat: AOSP 15 shell.te grants sock_file create ONLY for
 * shell_test_data_file (/data/local/tests), not for shell_data_file
 * (/data/local/tmp) - so the pathname bind is expected to fail on the AOSP
 * baseline and the PROBE_BIND lines decide where the socket can live (vendor
 * policy may extend this; errno EACCES with a matching avc denial is the
 * signature).
 *
 * Output is line-keyed for grepping:
 *   PROBE uid=... gid=... home=...
 *   PROBE exec_ok=1
 *   PROBE_SOCKPAIR ok=1 errno=0
 *   PROBE_BIND dir=... ok=1 errno=0
 *   PROBE_SUMMARY exec=1 sockpair=1 bind_ok=2
 *
 * Usage: probe_shell [extra-bind-dir ...]
 * Exit: 0 (informational; read the lines, not the exit status).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#define MAX_DIRS 12

static void mkdirs(char *path)
{
    /* Best-effort mkdir -p; failure surfaces in the bind errno. */
    for (char *p = path + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            (void)mkdir(path, 0755);
            *p = '/';
        }
    }
    (void)mkdir(path, 0755);
}

static int probe_bind(const char *dir)
{
    char path[256];
    char dircopy[256];
    if (snprintf(path, sizeof(path), "%s/probe-%d.sock", dir, (int)getpid())
            >= (int)sizeof(path)) {
        printf("PROBE_BIND dir=%s ok=0 errno=0 (path-too-long)\n", dir);
        return 0;
    }
    snprintf(dircopy, sizeof(dircopy), "%s", dir);
    mkdirs(dircopy); /* best-effort mkdir -p of the candidate dir */
    int ok = 0;
    int saved = 0;
    int s = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (s >= 0) {
        if (strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
            printf("PROBE_BIND dir=%s ok=0 errno=0 (sun_path-too-long)\n", dir);
            close(s);
            return 0;
        }
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
        unlink(path);
        if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) == 0)
            ok = 1;
        else
            saved = errno;
        close(s);
        unlink(path);
    } else {
        saved = errno;
    }
    printf("PROBE_BIND dir=%s ok=%d errno=%d\n", dir, ok,
           ok ? 0 : (saved ? saved : EACCES));
    return ok;
}

int main(int argc, char **argv)
{
    const char *home = getenv("HOME");
    printf("PROBE uid=%d gid=%d home=%s\n", getuid(), getgid(),
           home ? home : "(unset)");
    printf("PROBE exec_ok=1\n");

    int sv[2];
    int sockpair_ok = socketpair(AF_UNIX, SOCK_DGRAM, 0, sv) == 0;
    if (!sockpair_ok) {
        printf("PROBE_SOCKPAIR ok=0 errno=%d\n", errno);
    } else {
        printf("PROBE_SOCKPAIR ok=1 errno=0\n");
        close(sv[0]);
        close(sv[1]);
    }

    const char *defaults[] = {
        "/data/local/tmp",                    /* shell_data_file (AOSP baseline) */
        "/data/local/tmp/.uml/u3-probe",      /* HOME/uml_dir shaped candidates */
        "/data/local/tests/unittest/uml-probe" /* shell_test_data_file */
    };
    const char *dirs[MAX_DIRS];
    int ndirs = 0;
    for (size_t i = 0; i < sizeof(defaults) / sizeof(defaults[0]) && ndirs < MAX_DIRS; i++)
        dirs[ndirs++] = defaults[i];
    for (int i = 1; i < argc && ndirs < MAX_DIRS; i++)
        dirs[ndirs++] = argv[i];

    int bind_ok = 0;
    char oklist[512];
    oklist[0] = '\0';
    for (int i = 0; i < ndirs; i++) {
        char scratch[256];
        snprintf(scratch, sizeof(scratch), "%s", dirs[i]);
        if (probe_bind(scratch)) {
            bind_ok++;
            strncat(oklist, " ", sizeof(oklist) - strlen(oklist) - 1);
            strncat(oklist, dirs[i], sizeof(oklist) - strlen(oklist) - 1);
        }
    }

    printf("PROBE_SUMMARY exec=1 sockpair=%d bind_ok=%d bind_dirs=%s\n",
           sockpair_ok, bind_ok, bind_ok ? oklist : "(none)");
    return 0;
}
