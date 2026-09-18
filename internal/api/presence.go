package api

import "github.com/agentmirror/agentmirror/internal/protocol"

// registerPresence admits a successfully initialized mirror to the ref-scoped
// presence set and publishes the resulting counts to every live subscriber.
func (s *Server) registerPresence(sub *subscription) {
	if sub == nil || sub.ref == "" || sub.conn == nil || sub.clientType == "" {
		return
	}
	s.presenceMu.Lock()
	if sub.presenceRemoved {
		s.presenceMu.Unlock()
		return
	}
	if s.presenceSubs == nil {
		s.presenceSubs = make(map[string]map[*subscription]struct{})
	}
	set := s.presenceSubs[sub.ref]
	if set == nil {
		set = make(map[*subscription]struct{})
		s.presenceSubs[sub.ref] = set
	}
	if sub.presenceRegistered {
		s.presenceMu.Unlock()
		return
	}
	sub.presenceRegistered = true
	set[sub] = struct{}{}
	recipients, update := s.presenceSnapshotLocked(sub.ref)
	s.presenceMu.Unlock()
	s.sendPresence(recipients, update)
}

// unregisterPresence removes a mirror exactly once. It is safe for explicit
// unsubscribe, connection teardown, and relay termination to race.
func (s *Server) unregisterPresence(sub *subscription) {
	if sub == nil || sub.ref == "" {
		return
	}
	s.presenceMu.Lock()
	if !sub.presenceRegistered {
		sub.presenceRemoved = true
		s.presenceMu.Unlock()
		return
	}
	sub.presenceRegistered = false
	sub.presenceRemoved = true
	if set := s.presenceSubs[sub.ref]; set != nil {
		delete(set, sub)
		if len(set) == 0 {
			delete(s.presenceSubs, sub.ref)
		}
	}
	recipients, update := s.presenceSnapshotLocked(sub.ref)
	s.presenceMu.Unlock()
	s.sendPresence(recipients, update)
}

func (s *Server) presenceSnapshotLocked(ref string) ([]*wsConn, protocol.PresenceUpdate) {
	set := s.presenceSubs[ref]
	var mobileCount, desktopCount uint32
	recipients := make([]*wsConn, 0, len(set))
	for sub := range set {
		switch sub.clientType {
		case protocol.ClientTypeDesktop:
			desktopCount++
		default:
			mobileCount++
		}
		if sub.conn != nil {
			recipients = append(recipients, sub.conn)
		}
	}
	return recipients, protocol.PresenceUpdate{
		Ref:          ref,
		HasMobile:    mobileCount > 0,
		MobileCount:  mobileCount,
		DesktopCount: desktopCount,
	}
}

func (s *Server) sendPresence(recipients []*wsConn, update protocol.PresenceUpdate) {
	for _, conn := range recipients {
		conn.send(&update)
	}
}
