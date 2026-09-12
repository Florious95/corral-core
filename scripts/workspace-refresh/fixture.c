#include <stdio.h>
#include <unistd.h>
/* Controlled terminal workload with provider-shaped process identity.
 * No model/agent is launched. Real tmux and accepted nodeprobe are unmodified. */
int main(void) {
  setvbuf(stdout, NULL, _IONBF, 0);
  printf("\033]0;fixture idle\007WORKSPACE_REFRESH_READY\n");
  char line[4096]; unsigned n = 0;
  while (fgets(line, sizeof line, stdin)) printf("FIXTURE_INPUT_%u:%s", ++n, line);
  return 0;
}
