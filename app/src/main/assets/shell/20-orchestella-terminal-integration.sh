#!/bin/bash
# Bash-only, opt-in semantic markers for the guest's real prompt and submitted commands.
# No Android input is mirrored. Only a newly added Bash history entry may be annotated;
# HISTCONTROL/HISTIGNORE exclusions and silent password reads remain excluded.
[ -n "${BASH_VERSION:-}" ] || return 0
case "$-" in *i*) ;; *) return 0 ;; esac
case "${TERM:-}" in dumb|'') return 0 ;; esac

__orchestella_terminal_before_prompt() {
    local result=$?
    printf '\033]133;D;%s\007' "$result"
    __orchestella_terminal_history_index=$HISTCMD
    return "$result"
}

__orchestella_terminal_after_prompt() {
    local result=$?
    local begin='\[\e]133;A;orchestella=1\a\]'
    local end='\[\e]133;B\a\]'
    # Existing prompt hooks (including Starship) run first. Remove only our own wrappers
    # so a static PS1 and repeated environment sourcing never accumulate markers.
    PS1="${PS1#"$begin"}"
    PS1="${PS1%"$end"}"
    PS1="$begin$PS1$end"
    local continuation_begin='\[\e]133;B;continuation=1\a\]'
    local continuation_end='\[\e]133;B;continuation=2\a\]'
    PS2="${PS2#"$continuation_begin"}"
    PS2="${PS2%"$continuation_end"}"
    PS2="$continuation_begin${PS2-'> '}$continuation_end"
    return "$result"
}

__orchestella_terminal_before_command() {
    local entry command_text encoded HISTTIMEFORMAT='' LC_ALL=C
    # PS0 expands before execution. Unlike fc -ln -1, history 1 identifies THIS entry.
    # A command omitted by ignorespace/HISTIGNORE does not advance HISTCMD.
    if [[ $HISTCMD -gt ${__orchestella_terminal_history_index:-$HISTCMD} ]] \
            && [[ -o history ]] && command -v base64 >/dev/null 2>&1; then
        entry="$(builtin history 1)"
        if [[ $entry =~ ^[[:space:]]*[0-9]+[[:space:]]+(.*)$ ]]; then
            command_text=${BASH_REMATCH[1]}
            if [[ ${#command_text} -le 8192 ]]; then
                encoded="$(printf '%s' "$command_text" | base64 | tr -d '\r\n')"
                printf '\033]133;C;cmd=%s\007' "$encoded"
                return 0
            fi
        fi
    fi
    # Boundary only: do not archive an ignored/private command or stale history entry.
    printf '\033]133;C\007'
}

if [[ ${PS0:-} != *'__orchestella_terminal_before_command'* ]]; then
    PS0='$(__orchestella_terminal_before_command)'"${PS0:-}"
fi
if [[ ${PROMPT_COMMAND[*]:-} != *'__orchestella_terminal_before_prompt'* ]]; then
    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a '* ]]; then
        PROMPT_COMMAND=(__orchestella_terminal_before_prompt "${PROMPT_COMMAND[@]}" __orchestella_terminal_after_prompt)
    else
        PROMPT_COMMAND="__orchestella_terminal_before_prompt${PROMPT_COMMAND:+; $PROMPT_COMMAND}; __orchestella_terminal_after_prompt"
    fi
fi
