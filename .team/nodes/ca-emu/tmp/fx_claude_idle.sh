printf '\033[?1049h'          # 进 alt-screen（alt=1）
printf '\033[2J\033[H'
for i in $(seq 1 24); do printf '  claude idle row %02d  ..............................\n' "$i"; done
printf '\n> '
while :; do sleep 3600; done
