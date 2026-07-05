# Content/Profile Frontend UX TODO

## Contract

- [ ] No UUID is displayed anywhere in the UI.
- [ ] Users are displayed by `username` / nickname.
- [ ] Files are displayed by `file.name`, never by `blobId` or media id.
- [ ] Account remains the source of identity, auth, profile data, privacy, follows, and avatars.
- [ ] All app content is authenticated-only and redirects to Account with a correct back URL.

## Implementation Plan

| Area | Status | Notes |
| --- | --- | --- |
| Auth redirect helper | Done | Centralized Account redirect with current full URL as `redirect`. |
| UI display guards | Done | Added helpers that replace UUID-looking labels with safe titles. |
| Stories author enrichment | Done | Resolves story author username/avatar from Account `/users/{id}`. |
| Story rail UI | Done | Shows avatar + username, close-friends ring, seen state without UUID fallback. |
| Story archive lifecycle | Done | Expired stories remain queryable through archive; Flyway V3 adds `story_views` and archive indexes. |
| Story group viewer | Done | Viewer opens author queues, advances within the group, supports pause button/hold pause, and shows author/time metadata. |
| Story archive timeline | Done | Profile archive branch opens fullscreen horizontal timeline with preview cards and time navigation. |
| Story 60s media cap | Done | Recording auto-stops at 60s and uploaded video/audio carries trim metadata for the first minute. |
| Profile canvas post branches | Done | Profile posts are right-side compact canvas nodes. |
| Canvas collision avoidance | Done | Deterministic node placement now runs overlap resolution. |
| Settings location | Done | Settings action moved from profile canvas to burger menu Account link. |
| Post editor attachments | In progress | Hover no longer shows `media:*`; full inline contenteditable attachment chip behavior remains. |
| Search UX | In progress | Added tabs/filters and Account-backed user search; comment/search-service endpoint wiring remains. |
| Post renderer | Pending | Align published post rendering with editor attachment model. |
| Browser QA | Done | Validated home, burger/settings redirect, profile canvas post nodes, search, story viewer, archive branch, and archive timeline at `http://profile.localhost:8088`. |

## Done Log

- [x] Frontend tests pass.
- [x] Frontend build passes.
- [x] Services rebuilt/restarted.
- [x] Browser QA completed.
- [x] Story archive migration applied in compose.
