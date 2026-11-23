package io.github.nyzeep.orchestella.runtime.engineuml;

/**
 * Single source of truth for UML standalone guest runtime scripts shared by
 * ephemeral HOSTFS fixtures and persistent ext4 {@code ubd0} root volumes.
 */
final class UmlPersistentGuestScripts {
    private UmlPersistentGuestScripts() {}

    static String persistentUmCommandScript() {
        return """
                #!/bin/busybox sh
                set -u
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export PATH

                mkdir -p /tmp /run /root 2>/dev/null || :
                chmod 1777 /tmp 2>/dev/null || :
                if ! grep -q ' /tmp ' /proc/mounts 2>/dev/null; then
                    mount -t tmpfs -o mode=1777 tmpfs /tmp 2>/dev/null || :
                fi
                if ! grep -q ' /run ' /proc/mounts 2>/dev/null; then
                    mount -t tmpfs -o mode=0755 tmpfs /run 2>/dev/null || :
                fi

                mkdir -p /run/orchestella 2>/dev/null || :
                chmod 0700 /run/orchestella 2>/dev/null || :

                creds_dir=""
                for word in $(cat /proc/cmdline 2>/dev/null); do
                    case "$word" in
                        um.creds=*) creds_dir=${word#*=}; break ;;
                    esac
                done
                if [ -n "$creds_dir" ]; then
                    mkdir -p /run/orchestella-creds 2>/dev/null || :
                    if mount -t hostfs -o "$creds_dir" none /run/orchestella-creds 2>/dev/null; then
                        if [ -r /run/orchestella-creds/bridge.token ]; then
                            cp /run/orchestella-creds/bridge.token /run/orchestella/bridge.token 2>/dev/null || :
                            chmod 0600 /run/orchestella/bridge.token 2>/dev/null || :
                        fi
                        if [ -r /run/orchestella-creds/hostname ]; then
                            cp /run/orchestella-creds/hostname /run/orchestella/hostname 2>/dev/null || :
                            chmod 0644 /run/orchestella/hostname 2>/dev/null || :
                        fi
                        umount /run/orchestella-creds 2>/dev/null || :
                    fi
                fi

                /etc/orchestella/bridge-agent.sh &
                agent_pid=$!

                setsid_bin=/usr/bin/setsid
                if [ ! -x "$setsid_bin" ]; then
                    setsid_bin=$(command -v setsid 2>/dev/null || printf /bin/busybox)
                fi
                if [ "$(basename "$setsid_bin")" = "busybox" ]; then
                    "$setsid_bin" setsid /bin/sh -c 'exec </dev/tty0 >/dev/tty0 2>&1; exec /etc/orchestella/login-shell.sh'
                else
                    "$setsid_bin" /bin/sh -c 'exec </dev/tty0 >/dev/tty0 2>&1; exec /etc/orchestella/login-shell.sh'
                fi
                shell_rc=$?

                kill "$agent_pid" 2>/dev/null || :
                sync 2>/dev/null || :
                mount -o remount,ro / 2>/dev/null || :
                sync 2>/dev/null || :
                poweroff -f 2>/dev/null || :
                exit "$shell_rc"
                """;
    }

    static String persistentLoginShellScript(String defaultHostname) {
        return "#!/bin/sh\n"
                + "export TERM=xterm-256color\n"
                + "export HOME=/root\n"
                + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                + "export ENV=/etc/orchestella/ashrc\n"
                + "mkdir -p /root /run 2>/dev/null || :\n"
                + "target_hostname='" + defaultHostname + "'\n"
                + "if [ -r /run/orchestella/hostname ]; then\n"
                + "    runtime_host=$(tr -d '\\r\\n' </run/orchestella/hostname 2>/dev/null || :)\n"
                + "    if [ -n \"$runtime_host\" ]; then\n"
                + "        target_hostname=$runtime_host\n"
                + "    fi\n"
                + "elif [ -r /etc/hostname ]; then\n"
                + "    etc_host=$(tr -d '\\r\\n' </etc/hostname 2>/dev/null || :)\n"
                + "    if [ -n \"$etc_host\" ]; then\n"
                + "        target_hostname=$etc_host\n"
                + "    fi\n"
                + "fi\n"
                + "hostname \"$target_hostname\" 2>/dev/null || :\n"
                + "cd /root 2>/dev/null || :\n"
                + "if [ -r /run/orchestella-winsize ]; then\n"
                + "    read -r _rows _cols </run/orchestella-winsize 2>/dev/null || :\n"
                + "    if [ -n \"${_rows:-}\" ] && [ -n \"${_cols:-}\" ]; then\n"
                + "        stty rows \"$_rows\" cols \"$_cols\" </dev/tty0 2>/dev/null || :\n"
                + "    fi\n"
                + "fi\n"
                + "exec /bin/sh -i\n";
    }

    static String persistentBridgeAgentScript() {
        return """
                #!/bin/sh
                set -u
                TOKEN_FILE=/run/orchestella/bridge.token
                if [ ! -r "$TOKEN_FILE" ]; then
                    TOKEN_FILE=/etc/orchestella/bridge.token
                fi
                DEV=/dev/ttyS0

                i=0
                while [ ! -e "$DEV" ] && [ "$i" -lt 20 ]; do
                    sleep 0.05 2>/dev/null || sleep 1
                    i=$((i + 1))
                done
                [ -e "$DEV" ] || exit 1

                exec 3<"$DEV" 4>"$DEV" || exit 1
                stty raw -echo -icrnl -onlcr -isig <&3 2>/dev/null || :

                EXPECTED=$(tr -d '\\r\\n' <"$TOKEN_FILE" 2>/dev/null || :)
                [ ${#EXPECTED} -eq 64 ] || exit 1

                is_uint() {
                    case "$1" in
                        ''|*[!0-9]*) return 1 ;;
                        *) return 0 ;;
                    esac
                }

                is_hex64() {
                    [ ${#1} -eq 64 ] || return 1
                    case "$1" in
                        *[!0-9a-f]*) return 1 ;;
                        *) return 0 ;;
                    esac
                }

                authed=0
                while IFS= read -r raw_line <&3; do
                    line=$(printf '%s' "$raw_line" | tr -d '\\r')
                    [ -n "$line" ] || continue
                    case "$line" in
                        ORCHESTELLA/HELLO\\ *|DROIDLAYER/HELLO\\ *|ORCHESTELLA/HELLO|DROIDLAYER/HELLO)
                            set -- $line
                            if [ $# -ne 4 ] || ! is_uint "$2" || ! is_uint "$3" || ! is_hex64 "$4"; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR malformed-handshake\\n' >&4
                                exit 1
                            fi
                            if [ "$2" -gt "$3" ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR malformed-handshake\\n' >&4
                                exit 1
                            fi
                            if [ "$4" != "$EXPECTED" ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR unauthorized\\n' >&4
                                exit 3
                            fi
                            if [ "$2" -gt 1 ] || [ "$3" -lt 1 ]; then
                                authed=0
                                printf 'ORCHESTELLA/ERROR unsupported-version\\n' >&4
                                exit 2
                            fi
                            authed=1
                            printf 'ORCHESTELLA/READY 1\\n' >&4
                            ;;
                        ORCHESTELLA/PING|DROIDLAYER/PING)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            printf 'ORCHESTELLA/PONG 1\\n' >&4
                            ;;
                        ORCHESTELLA/RESIZE\\ *|DROIDLAYER/RESIZE\\ *)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            set -- $line
                            if [ $# -ne 3 ] || ! is_uint "$2" || ! is_uint "$3" || [ "$2" -lt 1 ] || [ "$2" -gt 1000 ] || [ "$3" -lt 1 ] || [ "$3" -gt 1000 ]; then
                                printf 'ORCHESTELLA/ERROR malformed-command\\n' >&4
                                continue
                            fi
                            mkdir -p /run 2>/dev/null || :
                            printf '%s %s\\n' "$3" "$2" >/run/orchestella-winsize 2>/dev/null || :
                            stty rows "$3" cols "$2" </dev/tty0 2>/dev/null || :
                            printf 'ORCHESTELLA/OK resize\\n' >&4
                            ;;
                        "ORCHESTELLA/POWER shutdown"|"ORCHESTELLA/POWER poweroff"|"DROIDLAYER/POWER shutdown"|"DROIDLAYER/POWER poweroff")
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                                continue
                            fi
                            printf 'ORCHESTELLA/OK power\\n' >&4
                            (sleep 0.05 2>/dev/null || :; sync 2>/dev/null || :; mount -o remount,ro / 2>/dev/null || :; sync 2>/dev/null || :; poweroff -f 2>/dev/null || :) &
                            exit 0
                            ;;
                        *)
                            if [ "$authed" != 1 ]; then
                                printf 'ORCHESTELLA/ERROR unauthenticated\\n' >&4
                            else
                                printf 'ORCHESTELLA/ERROR unsupported-command\\n' >&4
                            fi
                            ;;
                    esac
                done
                """;
    }

    static String ashRcScript() {
        return """
                # Orchestella interactive Alpine shell readiness (no OSC 133;A block mode; BusyBox ash lacks PS0/DEBUG)
                case "$-" in
                    *i*)
                        PS1='\\u@\\h:\\w\\$ '
                        printf '\\033]133;B;orchestella=prompt\\007'
                        ;;
                esac
                """;
    }
}
