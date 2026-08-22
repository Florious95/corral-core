draw() {
  printf '\033[2J\033[H'
  for i in $(seq 1 30); do printf '  redraw-tui row %02d cols=%s lines=%s\n' "$i" "${COLUMNS:-?}" "${LINES:-?}"; done
}
trap 'draw' WINCH
draw
while :; do sleep 3600 & wait $!; done
