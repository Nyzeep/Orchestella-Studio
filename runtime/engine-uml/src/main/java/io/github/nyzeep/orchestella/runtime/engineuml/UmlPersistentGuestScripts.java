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
        return forwardConnectionScript("#!/bin/sh", "/bin/busybox");
    }

    static String debianForwardConnectionScript() {
        return forwardConnectionScript("#!/usr/lib/orchestella/busybox sh", "/usr/lib/orchestella/busybox");
    }

    private static String forwardConnectionScript(String shellShebang, String busyboxBin) {
        return """
                @SHELL@
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
                nonce=$(@BUSYBOX@ od -An -N32 -tx1 /dev/urandom | @BUSYBOX@ tr -d ' \\n')
                is_hex64 "$nonce" || exit 1
                proof=$(printf 'G1:%s:%s' "$challenge" "$token" | @BUSYBOX@ sha256sum)
                printf 'G1 %s %s\\n' "${proof%% *}" "$nonce"

                IFS=' ' read -r -t 3 -n 80 request port proof extra || exit 1
                [ "$request" = F1 ] && [ -z "$extra" ] && is_hex64 "$proof" || exit 1
                case "$port" in ''|0*|*[!0-9]*) exit 1 ;; esac
                [ "${#port}" -le 5 ] && [ "$port" -le 65535 ] && [ "$port" -ne @RELAY_PORT@ ] || exit 1
                expected=$(printf 'F1:%s:%s:%s' "$nonce" "$port" "$token" | @BUSYBOX@ sha256sum)
                [ "$proof" = "${expected%% *}" ] || exit 1
                printf 'OK\\n'
                @BUSYBOX@ nc -w 60 127.0.0.1 "$port"
                """
                .replace("@SHELL@", shellShebang)
                .replace("@BUSYBOX@", busyboxBin)
                .replace("@RELAY_PORT@", Integer.toString(UmlNetworkProfile.GUEST_RELAY_PORT));
    }

    private static String loginShellScript(
            String defaultHostname,
            String envPath,
            boolean includeUtf8Locale,
            String execTail) {
        return "#!/bin/sh\n"
                + "export TERM=xterm-256color\n"
                + "export HOME=/root\n"
                + (includeUtf8Locale ? "export LANG=C.UTF-8\n" : "")
                + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n"
                + "export ENV=" + envPath + "\n"
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
                + execTail;
    }

    static String persistentLoginShellScript(String defaultHostname) {
        return loginShellScript(
                defaultHostname,
                "/etc/orchestella/ashrc",
                false,
                "exec /bin/sh -i\n");
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

    static String debianCredsSetupScript() {
        return """
                #!/bin/sh
                set -u
                BB=/usr/lib/orchestella/busybox
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export PATH

                "$BB" mkdir -p /run/orchestella /run/orchestella/forward-slots 2>/dev/null || :
                "$BB" chmod 0700 /run/orchestella 2>/dev/null || :

                creds_dir=""
                for word in $(cat /proc/cmdline 2>/dev/null); do
                    case "$word" in
                        um.creds=*) creds_dir=${word#*=}; break ;;
                    esac
                done
                if [ -n "$creds_dir" ]; then
                    "$BB" mkdir -p /run/orchestella-creds 2>/dev/null || :
                    if "$BB" mount -t hostfs -o "$creds_dir" none /run/orchestella-creds 2>/dev/null; then
                        if [ -r /run/orchestella-creds/bridge.token ]; then
                            "$BB" cp /run/orchestella-creds/bridge.token /run/orchestella/bridge.token 2>/dev/null || :
                            "$BB" chmod 0600 /run/orchestella/bridge.token 2>/dev/null || :
                        fi
                        if [ -r /run/orchestella-creds/hostname ]; then
                            "$BB" cp /run/orchestella-creds/hostname /run/orchestella/hostname 2>/dev/null || :
                            "$BB" chmod 0644 /run/orchestella/hostname 2>/dev/null || :
                        fi
                        "$BB" umount /run/orchestella-creds 2>/dev/null || :
                    fi
                fi
                if [ -r /run/orchestella/hostname ]; then
                    target_host=$("$BB" tr -d '\\r\\n' </run/orchestella/hostname 2>/dev/null || :)
                    if [ -n "$target_host" ]; then
                        "$BB" hostname "$target_host" 2>/dev/null || :
                    fi
                fi
                exit 0
                """;
    }

    static String debianGuestAgentLauncherScript() {
        return """
                #!/bin/sh
                set -u
                BB=/usr/lib/orchestella/busybox
                TOKEN_FILE=/run/orchestella/bridge.token
                if [ ! -r "$TOKEN_FILE" ]; then
                    TOKEN_FILE=/etc/orchestella/bridge.token
                fi
                DEV=/dev/ttyS0

                i=0
                while [ ! -e "$DEV" ] && [ "$i" -lt 40 ]; do
                    "$BB" sleep 0.05 2>/dev/null || "$BB" sleep 1
                    i=$((i + 1))
                done
                [ -e "$DEV" ] || exit 1

                exec <"$DEV" >"$DEV" || exit 1
                "$BB" stty raw -echo -icrnl -onlcr -isig <"$DEV" 2>/dev/null || :
                exec /usr/bin/orchestella-guest-agent --token-file "$TOKEN_FILE" --terminal-device /dev/tty0
                """;
    }

    static String debianUmlNetworkScript() {
        return """
                #!/bin/sh
                set -u
                BB=/usr/lib/orchestella/busybox
                "$BB" ip link set lo up 2>/dev/null || :
                "$BB" ip link set vec0 up 2>/dev/null || :
                if ! "$BB" ip addr show dev vec0 2>/dev/null | "$BB" grep -q '%s/%d'; then
                    "$BB" ip addr add %s/%d dev vec0 2>/dev/null || :
                fi
                if ! "$BB" ip route show default 2>/dev/null | "$BB" grep -q 'via %s'; then
                    "$BB" ip route add default via %s dev vec0 2>/dev/null || :
                fi
                "$BB" mkdir -p /run/orchestella /run/orchestella/forward-slots /run/systemd/resolve
                "$BB" chmod 0755 /run/systemd /run/systemd/resolve 2>/dev/null || :
                printf 'nameserver %s\\noptions timeout:3 attempts:4\\n' > /run/systemd/resolve/orchestella-resolv.conf
                "$BB" chmod 0644 /run/systemd/resolve/orchestella-resolv.conf 2>/dev/null || :
                "$BB" cp /run/systemd/resolve/orchestella-resolv.conf /run/systemd/resolve/stub-resolv.conf 2>/dev/null || :
                "$BB" cp /run/systemd/resolve/orchestella-resolv.conf /run/orchestella/resolv.conf 2>/dev/null || :
                if [ -L /etc/resolv.conf ]; then
                    if [ "$("$BB" readlink /etc/resolv.conf 2>/dev/null || :)" != "/run/systemd/resolve/orchestella-resolv.conf" ]; then
                        "$BB" rm -f /etc/resolv.conf
                        "$BB" ln -sf /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf 2>/dev/null || "$BB" cp /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf
                    fi
                elif [ ! -e /etc/resolv.conf ]; then
                    "$BB" ln -sf /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf 2>/dev/null || "$BB" cp /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf
                elif [ -f /etc/resolv.conf ]; then
                    if ! "$BB" mount -o bind /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf 2>/dev/null; then
                        "$BB" cp /run/systemd/resolve/orchestella-resolv.conf /etc/resolv.conf
                    fi
                fi
                exec "$BB" nc -ll -p %d -e /etc/orchestella/forward-connection.sh
                """.formatted(
                        UmlNetworkProfile.GUEST_ADDRESS, UmlNetworkProfile.PREFIX_LENGTH,
                        UmlNetworkProfile.GUEST_ADDRESS, UmlNetworkProfile.PREFIX_LENGTH,
                        UmlNetworkProfile.GUEST_GATEWAY, UmlNetworkProfile.GUEST_GATEWAY,
                        UmlNetworkProfile.GUEST_DNS,
                        UmlNetworkProfile.GUEST_RELAY_PORT);
    }

    static String debianVec0NetworkdConfig() {
        return """
                [Match]
                Name=vec0

                [Network]
                Address=%s/%d
                Gateway=%s
                DNS=%s
                """.formatted(
                        UmlNetworkProfile.GUEST_ADDRESS,
                        UmlNetworkProfile.PREFIX_LENGTH,
                        UmlNetworkProfile.GUEST_GATEWAY,
                        UmlNetworkProfile.GUEST_DNS);
    }

    static String debianLoginShellScript(String defaultHostname) {
        return loginShellScript(
                defaultHostname,
                "/etc/profile.d/20-orchestella-terminal-integration.sh",
                true,
                "if [ -x /bin/bash ]; then\n"
                        + "    exec /bin/bash -il\n"
                        + "fi\n"
                        + "exec /bin/sh -i\n");
    }

    static String debianTerminalIntegrationScript() {
        return """
                # Orchestella interactive Debian shell readiness marker
                case "$-" in
                    *i*)
                        if [ -n "${BASH_VERSION:-}" ]; then
                            _orchestella_prompt_emit() {
                                if [ "${_ORCHESTELLA_SYSTEMD_OSC_SENT:-0}" != 1 ] && [ -r /run/orchestella/systemd-ready ]; then
                                    _ORCHESTELLA_SYSTEMD_OSC_SENT=1
                                    printf '\\033]133;O;orchestella=systemd-ready\\007'
                                fi
                                printf '\\033]133;B;orchestella=prompt\\007'
                            }
                            case ";${PROMPT_COMMAND:-};" in
                                *";_orchestella_prompt_emit;"*) ;;
                                *)
                                    if [ -n "${PROMPT_COMMAND:-}" ]; then
                                        PROMPT_COMMAND="${PROMPT_COMMAND};_orchestella_prompt_emit"
                                    else
                                        PROMPT_COMMAND="_orchestella_prompt_emit"
                                    fi
                                    ;;
                            esac
                        else
                            PS1='\\u@\\h:\\w\\$ '
                            if [ "${_ORCHESTELLA_SYSTEMD_OSC_SENT:-0}" != 1 ] && [ -r /run/orchestella/systemd-ready ]; then
                                _ORCHESTELLA_SYSTEMD_OSC_SENT=1
                                printf '\\033]133;O;orchestella=systemd-ready\\007'
                            fi
                            printf '\\033]133;B;orchestella=prompt\\007'
                        fi
                        ;;
                esac
                """;
    }

    static String debianCredsUnit() {
        return """
                [Unit]
                Description=Orchestella UML Per-Generation Credentials Loader
                DefaultDependencies=no
                Before=orchestella-guest-agent.service orchestella-uml-network.service orchestella-console.service sysinit.target
                Conflicts=shutdown.target

                [Service]
                Type=oneshot
                RemainAfterExit=yes
                ExecStart=/usr/lib/orchestella/setup-creds.sh

                [Install]
                WantedBy=multi-user.target
                """;
    }

    static String debianGuestAgentUnit() {
        return """
                [Unit]
                Description=Orchestella UML Guest Control Agent
                DefaultDependencies=no
                Requires=orchestella-uml-creds.service
                After=orchestella-uml-creds.service
                Before=sysinit.target multi-user.target
                Conflicts=shutdown.target

                [Service]
                Type=simple
                ExecStart=/usr/lib/orchestella/guest-agent-launcher.sh
                Restart=on-failure
                RestartSec=1
                TimeoutStopSec=3

                [Install]
                WantedBy=multi-user.target
                """;
    }

    static String debianUmlNetworkUnit() {
        return """
                [Unit]
                Description=Orchestella UML Passt Network and Forwarder
                Requires=orchestella-uml-creds.service
                After=orchestella-uml-creds.service network-pre.target

                [Service]
                Type=simple
                ExecStart=/usr/lib/orchestella/uml-network.sh
                Restart=on-failure
                RestartSec=1
                TimeoutStopSec=3

                [Install]
                WantedBy=multi-user.target
                """;
    }

    static String debianConsoleUnit() {
        return """
                [Unit]
                Description=Orchestella Interactive Root Shell on tty0
                Requires=orchestella-uml-creds.service
                After=orchestella-uml-creds.service systemd-user-sessions.service
                Conflicts=getty@tty1.service console-getty.service shutdown.target

                [Service]
                Type=idle
                ExecStart=/etc/orchestella/login-shell.sh
                StandardInput=tty
                StandardOutput=tty
                StandardError=tty
                TTYPath=/dev/tty0
                TTYReset=no
                TTYVHangup=yes
                SendSIGHUP=yes
                KillMode=mixed
                TimeoutStopSec=3
                Restart=always
                RestartSec=1
                Environment=TERM=xterm-256color
                Environment=HOME=/root

                [Install]
                WantedBy=multi-user.target
                """;
    }

    static String debianSystemdReadyUnit() {
        return """
                [Unit]
                Description=Orchestella Systemd Multi-User Readiness Marker
                Requires=orchestella-uml-creds.service orchestella-guest-agent.service
                After=orchestella-uml-creds.service orchestella-guest-agent.service systemd-user-sessions.service

                [Service]
                Type=oneshot
                RemainAfterExit=yes
                ExecStart=/usr/lib/orchestella/busybox sh -c 'mkdir -p /run/orchestella && printf "SYSTEMD_READY\\n" > /run/orchestella/systemd-ready && printf "\\nORCHESTELLA_SYSTEMD_READY target=multi-user.target pid1=%s\\n" "$(cat /proc/1/comm 2>/dev/null || printf unknown)" > /dev/kmsg 2>/dev/null || :'

                [Install]
                WantedBy=multi-user.target
                """;
    }
}
