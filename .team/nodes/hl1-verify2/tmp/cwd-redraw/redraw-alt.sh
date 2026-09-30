# sourced inside exec claude (symlink to bash) so comm stays claude
# trap WINCH: clear then immediately redraw marker fullscreen at current size
redraw() {
  cols=$(tput cols 2>/dev/null || echo 80)
  rows=$(tput lines 2>/dev/null || echo 24)
  printf '\033[H\033[2J'
  i=0
  while [ "$i" -lt "$rows" ]; do
    printf 'REDRAW_ALT_MARKER_092 cols=%s rows=%s line=%s\n' "$cols" "$rows" "$i"
    i=$((i + 1))
  done
}
printf '\033[?1049h\033[?25l'
trap 'redraw' WINCH
redraw
while :; do read -r -t 3600 || true; done
