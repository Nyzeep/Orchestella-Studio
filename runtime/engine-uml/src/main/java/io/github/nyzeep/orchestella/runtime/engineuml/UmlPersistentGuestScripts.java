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

                """ + startGuestNetwork() + """
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

                kill "$agent_pid" "$net_pid" 2>/dev/null || :
                umount /etc/resolv.conf 2>/dev/null || :
                sync 2>/dev/null || :
                mount -o remount,ro / 2>/dev/null || :
                sync 2>/dev/null || :
                poweroff -f 2>/dev/null || :
                exit "$shell_rc"
                """;
    }

    static String startGuestNetwork() {
        return """
                # The network is optional. Never wait for a host resolver or DHCP before login.
                (
                    /bin/busybox ip link set lo up
                    /bin/busybox ip link set vec0 up || exit
                    /bin/busybox ip addr add %s/%d dev vec0 || exit
                    /bin/busybox ip route add default via %s dev vec0 || exit
                    mkdir -p /run/orchestella
                    printf 'nameserver %s\\noptions timeout:2 attempts:2\\n' > /run/orchestella/resolv.conf
                    mkdir -p /run/orchestella/forward-slots
                    # A bind mount keeps the user volume's resolver configuration after shutdown.
                    # Touch only a missing regular resolver target from the Alpine image.
                    if [ ! -e /etc/resolv.conf ] && [ ! -L /etc/resolv.conf ]; then
                        touch /etc/resolv.conf
                    fi
                    if [ -f /etc/resolv.conf ] && [ ! -L /etc/resolv.conf ]; then
                        if ! mount -o bind /run/orchestella/resolv.conf /etc/resolv.conf &&
                                [ ! -s /etc/resolv.conf ]; then
                            cp /run/orchestella/resolv.conf /etc/resolv.conf
                        fi
                    fi
                    exec /bin/busybox nc -ll -p %d -e /etc/orchestella/forward-connection.sh
                ) </dev/null >/dev/null 2>&1 &
                net_pid=$!

                """.formatted(
                        UmlNetworkProfile.GUEST_ADDRESS, UmlNetworkProfile.PREFIX_LENGTH,
                        UmlNetworkProfile.GUEST_GATEWAY, UmlNetworkProfile.GUEST_DNS,
                        UmlNetworkProfile.GUEST_RELAY_PORT);
    }

    static String forwardConnectionScript() {
        return """
                #!/bin/sh
                set -u
                token_file=/run/orchestella/bridge.token
                if [ ! -r "$token_file" ]; then
                    token_file=/etc/orchestella/bridge.token
                fi
                IFS= read -r token < "$token_file" || exit 1

                is_hex64() {
                    [ "${#1}" -eq 64 ] || return 1
                    case "$1" in *[!0-9a-f]*) return 1 ;; esac
                }
                is_hex64 "$token" || exit 1
                slot=
                for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
                    candidate=/run/orchestella/forward-slots/$i
                    if mkdir "$candidate" 2>/dev/null; then
                        slot=$candidate
                        break
                    fi
                done
                [ -n "$slot" ] || exit 1
                trap 'rmdir "$slot" 2>/dev/null' EXIT

                # Authenticate both ends before relaying application bytes. The token
                # is never written to the socket, command line, environment or logs.
                IFS=' ' read -r -t 3 -n 72 hello challenge extra || exit 1
                [ "$hello" = H1 ] && [ -z "$extra" ] && is_hex64 "$challenge" || exit 1
                nonce=$(/bin/busybox od -An -N32 -tx1 /dev/urandom | /bin/busybox tr -d ' \\n')
                is_hex64 "$nonce" || exit 1
                proof=$(printf 'G1:%s:%s' "$challenge" "$token" | /bin/busybox sha256sum)
                printf 'G1 %s %s\\n' "${proof%% *}" "$nonce"

                IFS=' ' read -r -t 3 -n 80 request port proof extra || exit 1
                [ "$request" = F1 ] && [ -z "$extra" ] && is_hex64 "$proof" || exit 1
                case "$port" in ''|0*|*[!0-9]*) exit 1 ;; esac
                [ "${#port}" -le 5 ] && [ "$port" -le 65535 ] && [ "$port" -ne @RELAY_PORT@ ] || exit 1
                expected=$(printf 'F1:%s:%s:%s' "$nonce" "$port" "$token" | /bin/busybox sha256sum)
                [ "$proof" = "${expected%% *}" ] || exit 1
                printf 'OK\\n'
                /bin/busybox nc -w 60 127.0.0.1 "$port"
                """.replace("@RELAY_PORT@", Integer.toString(UmlNetworkProfile.GUEST_RELAY_PORT));
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
