package api

import (
	"bufio"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"unicode"
)

// ---
// purpose: provider 匹配器的页脚维——从共读语料取针，不把「查不到」写成 0
// contract:
//   provides:
//     - name: backgroundTasksFor
//       what: 有规则且命中=N；有规则未命中=0；语料无该 provider 的 footer 行=unknown
//   depends:
//     - tools/nodeprobe/fixtures/titles.tsv
// boundary:
//   - 不把 background_tasks 折进 status
//   - 具体页脚短语只存在语料
//   - 加新 provider 只加语料（同一种匹配）不必改本文件算法
// maturity: wired
// ---

type backgroundTasks struct {
	unknown bool
	count   uint32
}

func bgUnknown() backgroundTasks { return backgroundTasks{unknown: true} }
func bgCount(n uint32) backgroundTasks {
	return backgroundTasks{count: n}
}

func (b backgroundTasks) String() string {
	if b.unknown {
		return "unknown"
	}
	return strconv.FormatUint(uint64(b.count), 10)
}

func (b backgroundTasks) equalWant(want string) bool {
	return b.String() == want
}

type footerRules struct {
	needles []string
}

var (
	footerOnce  sync.Once
	footerTable map[string]footerRules
	footerErr   error
)

func loadFooterRules(path string) (map[string]footerRules, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	out := map[string]footerRules{}
	lineNo := 0
	for sc.Scan() {
		lineNo++
		line := sc.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.Split(line, "\t")
		if len(parts) != 4 {
			return nil, errFooterFormat(lineNo, len(parts))
		}
		payload, want, provider, kind := parts[0], parts[1], parts[2], parts[3]
		if kind != "footer" {
			if kind != "title" {
				return nil, errFooterKind(lineNo, kind)
			}
			continue
		}
		// want=unknown is a corpus assertion (no rule), not a rule row.
		// Registering it would make missing-rule look like Count(0).
		if want == "unknown" {
			continue
		}
		r := out[provider]
		if n, err := strconv.ParseUint(want, 10, 32); err == nil && n >= 1 {
			r.needles = append(r.needles, payload)
		}
		out[provider] = r
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

type footerFormatError struct {
	line, fields int
}

func errFooterFormat(line, fields int) error {
	return footerFormatError{line: line, fields: fields}
}

func (e footerFormatError) Error() string {
	return "titles.tsv:" + strconv.Itoa(e.line) + ": need 4 fields payload<TAB>want<TAB>provider<TAB>kind, got " + strconv.Itoa(e.fields)
}

type footerKindError struct {
	line int
	kind string
}

func errFooterKind(line int, kind string) error {
	return footerKindError{line: line, kind: kind}
}

func (e footerKindError) Error() string {
	return "titles.tsv:" + strconv.Itoa(e.line) + ": unknown kind " + e.kind + " (want title|footer)"
}

func footerRulesCached() map[string]footerRules {
	footerOnce.Do(func() {
		path := findTitlesTSV()
		if path == "" {
			footerErr = errFooterKind(0, "missing-corpus")
			footerTable = map[string]footerRules{}
			return
		}
		footerTable, footerErr = loadFooterRules(path)
		if footerTable == nil {
			footerTable = map[string]footerRules{}
		}
	})
	return footerTable
}

func findTitlesTSV() string {
	var starts []string
	if wd, err := os.Getwd(); err == nil {
		starts = append(starts, wd)
	}
	if _, file, _, ok := runtime.Caller(0); ok {
		starts = append(starts, filepath.Dir(file))
	}
	for _, start := range starts {
		dir := start
		for i := 0; i < 10; i++ {
			cand := filepath.Join(dir, "tools", "nodeprobe", "fixtures", "titles.tsv")
			if st, err := os.Stat(cand); err == nil && !st.IsDir() {
				return cand
			}
			parent := filepath.Dir(dir)
			if parent == dir {
				break
			}
			dir = parent
		}
	}
	return ""
}

// backgroundTasksFor is the provider matcher. Core must not name a CLI phrase.
func backgroundTasksFor(providerID, footer string) backgroundTasks {
	table := footerRulesCached()
	r, ok := table[providerID]
	if !ok {
		return bgUnknown()
	}
	for _, needle := range r.needles {
		if strings.Contains(footer, needle) {
			return bgCount(countBeforeNeedle(footer, needle))
		}
	}
	return bgCount(0)
}

func countBeforeNeedle(footer, needle string) uint32 {
	idx := strings.Index(footer, needle)
	if idx < 0 {
		return 1
	}
	before := footer[:idx]
	n := uint32(0)
	found := false
	for _, r := range before {
		if r >= '0' && r <= '9' {
			n = n*10 + uint32(r-'0')
			found = true
		} else if found && !unicode.IsSpace(r) {
			n = 0
			found = false
		}
	}
	if !found || n == 0 {
		return 1
	}
	return n
}
