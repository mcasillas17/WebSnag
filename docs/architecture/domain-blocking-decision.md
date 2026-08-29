# Domain Blocking Decision

WebSnag does not implement website or domain blocking. A browser-independent blocker would need
network traffic interception (for example a VPN) or accessibility content inspection. Both would
expand the app's privacy scope beyond its current local focus-enforcement purpose. WebSnag keeps
`canRetrieveWindowContent=false`, declares no `INTERNET` permission, and does not inspect traffic.

This is intentional product scope, not a partially implemented website blocker.
