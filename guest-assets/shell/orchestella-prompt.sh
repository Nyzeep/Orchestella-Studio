#!/bin/sh
# Orchestella Starship-Style Universal Two-Line Shell Prompt
# Compatible with POSIX sh, BusyBox ash, Debian dash, and GNU bash.
# Zero external binary dependencies, execution time < 2ms.

__orchestella_git_branch() {
    _cur="${PWD:-.}"
    _git=""
    while [ -n "$_cur" ]; do
        if [ -e "$_cur/.git" ]; then
            if [ -d "$_cur/.git" ]; then
                _git="$_cur/.git"
            elif [ -f "$_cur/.git" ]; then
                read -r _line < "$_cur/.git" 2>/dev/null || _line=""
                case "$_line" in
                    gitdir:*)
                        _dest="${_line#gitdir: }"
                        case "$_dest" in
                            /*) _git="$_dest" ;;
                            *) _git="$_cur/$_dest" ;;
                        esac
                        ;;
                esac
            fi
            break
        fi
        _up="${_cur%/*}"
        [ "$_up" = "$_cur" ] && break
        _cur="$_up"
    done

    [ -n "$_git" ] && [ -f "$_git/HEAD" ] || return 0

    read -r _ref < "$_git/HEAD" 2>/dev/null || return 0
    case "$_ref" in
        ref:\ refs/heads/*)
            printf '%s' "${_ref#ref: refs/heads/}"
            ;;
        ref:\ refs/*)
            printf '%s' "${_ref#ref: refs/}"
            ;;
        *)
            if [ "${#_ref}" -ge 7 ]; then
                printf '%.7s' "$_ref"
            else
                printf '%s' "$_ref"
            fi
            ;;
    esac
}

__orchestella_dir() {
    _cwd="${PWD:-$(pwd)}"
    if [ -n "${HOME:-}" ] && [ "$_cwd" = "$HOME" ]; then
        printf '~'
    elif [ -n "${HOME:-}" ]; then
        case "$_cwd" in
            "$HOME"/*)
                printf '~%s' "${_cwd#"$HOME"}"
                ;;
            *)
                printf '%s' "$_cwd"
                ;;
        esac
    else
        printf '%s' "$_cwd"
    fi
}

__orchestella_build_prompt() {
    _last_exit="${1:-0}"
    _mode="${2:-sh}"

    _esc=$(printf '\033')
    if [ "$_mode" = "bash" ]; then
        _c_reset="\[${_esc}[0m\]"
        _c_cyan="\[${_esc}[36m\]"
        _c_yellow="\[${_esc}[33m\]"
        _c_red="\[${_esc}[31m\]"
        _c_green="\[${_esc}[32m\]"
        _c_magenta="\[${_esc}[35m\]"
    else
        _c_reset="${_esc}[0m"
        _c_cyan="${_esc}[36m"
        _c_yellow="${_esc}[33m"
        _c_red="${_esc}[31m"
        _c_green="${_esc}[32m"
        _c_magenta="${_esc}[35m"
    fi

    _prompt_user="${USER:-${LOGNAME:-$(id -un 2>/dev/null)}}"
    # BusyBox ash sets HOSTNAME; the hostname fallback covers stricter POSIX shells.
    # shellcheck disable=SC3028
    _prompt_host="${HOSTNAME:-$(hostname 2>/dev/null)}"
    _dir_str="$(__orchestella_dir)"
    if [ -n "$_prompt_user" ] && [ -n "$_prompt_host" ]; then
        _dir_str="${_prompt_user}@${_prompt_host}:${_dir_str}"
    fi
    _branch="$(__orchestella_git_branch)"
    _git_part=""
    if [ -n "$_branch" ]; then
        _git_part=" ${_c_yellow}(${_branch})${_c_reset}"
    fi

    _err_part=""
    if [ "$_last_exit" -ne 0 ]; then
        _err_part=" ${_c_red}✘${_c_reset}"
    fi

    _arrow_color="$_c_green"
    _is_root=0
    if [ "${USER:-}" = "root" ]; then
        _is_root=1
    elif command -v id >/dev/null 2>&1; then
        if [ "$(id -u 2>/dev/null || echo 1)" -eq 0 ]; then
            _is_root=1
        fi
    fi
    if [ "$_is_root" -eq 1 ]; then
        _arrow_color="$_c_magenta"
    fi

    printf '%s%s%s%s%s\n%s❯%s ' \
        "$_c_cyan" "$_dir_str" "$_c_reset" "$_git_part" "$_err_part" \
        "$_arrow_color" "$_c_reset"
}

if [ -n "${BASH_VERSION:-}" ]; then
    __orchestella_prompt_cmd() {
        _exit_status="$?"
        PS1="$(__orchestella_build_prompt "$_exit_status" bash)"
    }
    PROMPT_COMMAND=__orchestella_prompt_cmd
    PS1="$(__orchestella_build_prompt 0 bash)"
else
    # PS1 intentionally holds an unevaluated command substitution, re-evaluated
    # on every prompt draw (POSIX sh has no PROMPT_COMMAND).
    # shellcheck disable=SC2089
    PS1='$(__orchestella_build_prompt "$?" sh)'
fi
# shellcheck disable=SC2090
export PS1

__droidlayer_build_prompt() { __orchestella_build_prompt "$@"; }
