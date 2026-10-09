#!/bin/sh
# Orchestella unified environment initialization
# Installed into /etc/profile.d/00-orchestella-env.sh for Alpine (ash) and Debian (bash)

# Standardized LS_COLORS palette
export LS_COLORS="rs=0:di=01;34:ln=01;36:mh=00:pi=40;33:so=01;35:do=01;35:bd=40;33;01:cd=40;33;01:or=40;31;01:mi=00:su=37;41:sg=30;43:ca=30;41:tw=30;42:ow=34;42:st=37;44:ex=01;32:*.tar=01;31:*.tgz=01;31:*.arc=01;31:*.arj=01;31:*.taz=01;31:*.lha=01;31:*.lz4=01;31:*.lzh=01;31:*.lzma=01;31:*.tlz=01;31:*.txz=01;31:*.tzo=01;31:*.t7z=01;31:*.zip=01;33:*.z=01;31:*.dz=01;31:*.gz=01;31:*.lrz=01;31:*.lz=01;31:*.lzo=01;31:*.xz=01;31:*.zst=01;31:*.tzst=01;31:*.bz2=01;31:*.bz=01;31:*.tbz=01;31:*.tbz2=01;31:*.tz=01;31:*.deb=01;31:*.rpm=01;31:*.jar=01;33:*.war=01;33:*.7z=01;33:*.rar=01;33:*.c=00;36:*.h=00;36:*.cpp=00;36:*.hpp=00;36:*.cc=00;36:*.cxx=00;36:*.rs=01;33:*.py=00;33:*.go=00;34:*.java=00;35:*.kt=01;35:*.kts=01;35:*.js=00;32:*.ts=01;32:*.jsx=00;32:*.tsx=01;32:*.sh=01;32:*.bash=01;32:*.json=00;33:*.xml=00;33:*.yaml=00;33:*.yml=00;33:*.toml=00;33:*.md=00;37:*.txt=00;37"
export LSCOLORS="Gxfxcxdxbxegedabagacad"

# Outbound network proxy relay (Orchestella Bridge proxy)
export http_proxy="${http_proxy:-http://127.0.0.1:8080}"
export https_proxy="${https_proxy:-http://127.0.0.1:8080}"
export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:8080}"
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:8080}"
export all_proxy="${all_proxy:-socks5://127.0.0.1:1080}"
export ALL_PROXY="${ALL_PROXY:-socks5://127.0.0.1:1080}"

# Load high-frequency aliases
if [ -f /etc/orchestella/shell/aliases.sh ]; then
    # shellcheck disable=SC1091
    . /etc/orchestella/shell/aliases.sh
elif [ -f /etc/droidlayer/shell/aliases.sh ]; then
    # shellcheck disable=SC1091
    . /etc/droidlayer/shell/aliases.sh
else
    alias ll='ls -lah --color=auto'
    alias la='ls -A --color=auto'
    alias l='ls -CF --color=auto'
    alias grep='grep --color=auto'
    alias df='df -h'
    alias free='free -m'
fi

# Load Starship-style prompt
if [ -f /etc/orchestella/shell/orchestella-prompt.sh ]; then
    # shellcheck disable=SC1091
    . /etc/orchestella/shell/orchestella-prompt.sh
elif [ -f /etc/droidlayer/shell/droidlayer-prompt.sh ]; then
    # shellcheck disable=SC1091
    . /etc/droidlayer/shell/droidlayer-prompt.sh
elif [ -f "$(dirname "$0")/orchestella-prompt.sh" ]; then
    # shellcheck disable=SC1090
    . "$(dirname "$0")/orchestella-prompt.sh"
elif [ -f "$(dirname "$0")/droidlayer-prompt.sh" ]; then
    # shellcheck disable=SC1090
    . "$(dirname "$0")/droidlayer-prompt.sh"
fi

# Load graphics acceleration environment
if [ -f /etc/profile.d/10-orchestella-graphics.sh ]; then
    # shellcheck disable=SC1091
    . /etc/profile.d/10-orchestella-graphics.sh
elif [ -f /etc/profile.d/10-droidlayer-graphics.sh ]; then
    # shellcheck disable=SC1091
    . /etc/profile.d/10-droidlayer-graphics.sh
elif [ -f /etc/orchestella/shell/10-orchestella-graphics.sh ]; then
    # shellcheck disable=SC1091
    . /etc/orchestella/shell/10-orchestella-graphics.sh
elif [ -f /etc/droidlayer/shell/10-droidlayer-graphics.sh ]; then
    # shellcheck disable=SC1091
    . /etc/droidlayer/shell/10-droidlayer-graphics.sh
elif [ -f "$(dirname "$0")/10-orchestella-graphics.sh" ]; then
    # shellcheck disable=SC1090
    . "$(dirname "$0")/10-orchestella-graphics.sh"
elif [ -f "$(dirname "$0")/10-droidlayer-graphics.sh" ]; then
    # shellcheck disable=SC1090
    . "$(dirname "$0")/10-droidlayer-graphics.sh"
fi
