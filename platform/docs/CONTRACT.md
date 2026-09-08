# Sarab platform contract v1

Root repository: C:/Users/TechTroniX/Documents/GitHub/ar_map. Platform root is platform/. Existing Android camera/navigation stays intact. Server will deploy an isolated stack on a NEW port, never modifying/restarting existing services. Root owns deployment and app integration. JSON API prefix /api/v1. ISO timestamps in UTC, coordinates only when user explicitly enables sharing. Latest location only, no location history. AI provider secrets server-only.

## Authentication
- POST /api/v1/auth/login {username,password} -> {token,user:{id,name,role}}. Dashboard holds token in sessionStorage, Bearer auth. Admin bootstrap credentials server env. Rate limit login.
- POST /api/v1/auth/logout (Bearer) -> 204.
- GET /api/v1/me -> {user:{id,name,role}}.
- Dashboard admin creates groups and short-lived enrollment codes. A device enrolls with code/name/language and receives independent device bearer token. No public user list or open telemetry.

## Groups and devices
- GET /api/v1/groups -> {groups:[{id,name,memberCount}]}
- POST /api/v1/groups {name} -> {group:{id,name,memberCount}}
- POST /api/v1/groups/:id/enrollment -> {code,expiresAt}; code 8+ random characters, 24h TTL, permit group enrollment max20 uses.
- POST /api/v1/devices/enroll {code,name,language} -> {token,device:{id,name,groupId},group:{id,name}}
- GET /api/v1/devices -> {devices:[{id,name,groupId,groupName,language,status,lastSeenAt,location:{lat,lng,accuracyM,recordedAt}|null,batteryPercent,cameraConnected,imuTracking,watchConnected,destinationName,lap:{mode,count,target,confidence}|null,sharingEnabled}]}
- POST /api/v1/device/telemetry (deviceBearer) {sharingEnabled,location:{lat,lng,accuracyM,recordedAt}|null,batteryPercent,cameraConnected,imuTracking,watchConnected,destinationName,lap:{mode,count,target,confidence}|null,status:'active'|'needs_help'|'paused'} -> {ok:true}
- POST /api/v1/device/privacy {sharingEnabled:false} immediately clears location -> {ok:true}.
- GET /api/v1/device/profile -> {device:{id,name,groupId},group:{id,name}}

## Alerts
- GET /api/v1/alerts -> {alerts:[{id,groupId,sourceDeviceId,sourceName,kind:'help'|'regroup'|'message',message,createdAt,recipients:[{deviceId,name,deliveredAt,acknowledgedAt}]}]}
- POST /api/v1/alerts (admin) {groupId,deviceIds?:[],kind,message} -> {alert:...}. Empty omitted deviceIds means all group.
- POST /api/v1/device/alerts (device) {kind:'help'|'regroup',message,clientId} -> {alert:...}; server derives own group, idempotent clientId.
- GET /api/v1/device/alerts -> {alerts:[{id,kind,message,createdAt,sourceName}]}; returns pending unacknowledged fresh alerts only; explicit delivery semantics.
- POST /api/v1/device/alerts/:id/delivered -> {ok:true}
- POST /api/v1/device/alerts/:id/ack -> {ok:true}

## Assistant
- POST /api/v1/assistant (device) {message,language,context:{destinationId,destinationName,remainingMeters,lap},destinations:[{id,name,aliases?:[],distanceMeters?:number}],history:[{role:'user'|'assistant',content}]} -> {reply,language,action:{type:'navigate'|'none',destinationId:string|null},provider:'openai'|'local',requiresConfirmation:boolean}. Never invent destinations/routes. Server uses OpenAI Responses structured outputs when configured, validates action against provided catalog. No automatic alerts from assistant. Deterministic limited offline/provider-unavailable fallback disclosed via provider=local. Do not fake general AI success. Must handle Arabic/English/Urdu/Indonesian/Turkish and language tags generally.
- GET /api/v1/health -> {status:'ok',aiConfigured:boolean,version:'3.0'} no secrets.

## Dashboard
Arabic RTL polished responsive application with real authenticated data, map of only shared last-known locations, explicit offline/stale/accuracy status, counts, group/enrollment management, alert compose and delivery/ack status. Demo is optional explicitly labeled opt-in and never sends data into real server. No invented real users. Poll every5s authenticated fetch; no SSE required v1.

## Wear OS phone data layer (same app id/signature on watch)
message path /sarab/watch/event payload JSON {id,type:'lap'|'help'|'ack'|'status', ...}; lap fields {mode:'tawaf'|'sai',count,target:7,confidence:'manual'|'estimated',sessionId}; ack {alertId}; status {batteryPercent}.
Phone -> watch /sarab/watch/alert {id,kind,message,sourceName,createdAt}; must dedupe persistent IDs before vibration. Watch sends ack on user tap, not merely delivery. Phone can reply /sarab/watch/state {groupName,connected,lastSyncedAt}. Watch event help uses id as backend clientId. Counts locally persisted, manual increment/undo plus optional conservative estimated laps where evidence supports it; clearly label estimates, never claim religious completion or GPS precision.
