# sourced inside exec claude (symlink to bash) so comm stays claude
printf '\033[?1049h\033[H\033[2JSTATIC_ALT_MARKER_092\n'
trap 'printf "\033[2J\033[H"' WINCH
while :; do read -r -t 3600 || true; done
