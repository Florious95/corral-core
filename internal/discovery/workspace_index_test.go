package discovery

import (
	"reflect"
	"sync"
	"testing"
)

func indexedModel(panes ...Pane) *Model { return buildModel(panes) }

func TestWorkspaceIndexManyToManyAndDetachedRoutes(t *testing.T) {
	x := NewWorkspaceIndex()
	x.Observe(indexedModel(
		Pane{Socket: "/s/1", PaneID: "%0", CWD: "/A"},
		Pane{Socket: "/s/1", PaneID: "%1", CWD: "/B"},
		Pane{Socket: "/s/2", PaneID: "%0", CWD: "/A"},
	), nil)
	if got := x.Sockets("/A"); !reflect.DeepEqual(got, []string{"/s/1", "/s/2"}) {
		t.Fatalf("A routes = %v", got)
	}
	if got := x.Sockets("/B"); !reflect.DeepEqual(got, []string{"/s/1"}) {
		t.Fatalf("B routes = %v", got)
	}
	routes := x.Sockets("/A")
	routes[0] = "mutated"
	if x.Sockets("/A")[0] != "/s/1" {
		t.Fatal("caller mutated shared routing state")
	}
	if len(x.Sockets("/unknown")) != 0 {
		t.Fatal("unknown workspace widened to host sockets")
	}
}

func TestWorkspaceIndexOmittedSlowSocketIsNotDeletion(t *testing.T) {
	x := NewWorkspaceIndex()
	x.Observe(indexedModel(Pane{Socket: "/s/1", CWD: "/A"}), nil)
	x.Observe(indexedModel(Pane{Socket: "/s/2", CWD: "/B"}), nil)
	if len(x.Sockets("/A")) != 1 {
		t.Fatal("best-effort omission erased route to unavailable target")
	}
	x.Observe(&Model{}, []string{"/s/1"})
	if len(x.Sockets("/A")) != 0 || len(x.Sockets("/B")) != 1 {
		t.Fatal("confirmed socket deletion was not precise")
	}
}

func TestWorkspaceIndexMoveAndNewWorkspaceReplaceSocketRoutes(t *testing.T) {
	x := NewWorkspaceIndex()
	x.Observe(indexedModel(Pane{Socket: "/s/1", CWD: "/A"}), nil)
	x.Observe(indexedModel(Pane{Socket: "/s/1", CWD: "/B"}), nil)
	if len(x.Sockets("/A")) != 0 || len(x.Sockets("/B")) != 1 {
		t.Fatal("successful inventory did not replace socket workspace membership")
	}
}

func TestWorkspaceIndexReadyRequiresActualInventory(t *testing.T) {
	x := NewWorkspaceIndex()
	x.Observe(nil, nil)
	select {
	case <-x.Ready():
		t.Fatal("nil/failure established an empty inventory")
	default:
	}
	x.Observe(&Model{}, nil)
	select {
	case <-x.Ready():
	default:
		t.Fatal("successful empty inventory did not release cold waiters")
	}
}

func TestWorkspaceIndexConcurrentReadersAndPublisher(t *testing.T) {
	x := NewWorkspaceIndex()
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for n := 0; n < 100; n++ {
				x.Observe(indexedModel(Pane{Socket: "/s/1", CWD: "/A"}), nil)
				_ = x.Sockets("/A")
				_ = x.KnownSockets()
			}
		}()
	}
	wg.Wait()
}
