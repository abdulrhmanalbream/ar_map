export interface User { id: string; name: string; role: string }
export interface Group { id: string; name: string; memberCount: number }
export interface Location { lat: number; lng: number; accuracyM: number; recordedAt: string }
export interface Device {
  id: string; name: string; groupId: string; groupName: string; language: string;
  status: 'active' | 'needs_help' | 'paused' | 'offline'; lastSeenAt: string | null;
  location: Location | null; batteryPercent: number | null; cameraConnected: boolean;
  imuTracking: boolean; watchConnected: boolean; destinationName: string | null;
  lap: { mode: string; count: number; target: number; confidence: string } | null;
  sharingEnabled: boolean;
}
export interface Recipient { deviceId: string; name: string; deliveredAt: string | null; acknowledgedAt: string | null }
export interface Alert {
  id: string; groupId: string; sourceDeviceId: string | null; sourceName: string;
  kind: 'help' | 'regroup' | 'message'; message: string; createdAt: string; recipients: Recipient[];
  adminAcknowledgedAt?: string | null; adminAcknowledgedBy?: string | null;
}
export interface Health { status: string; aiConfigured: boolean; version: string }
export interface Snapshot { devices: Device[]; groups: Group[]; alerts: Alert[]; health: Health | null }
export type Filter = 'all' | 'active' | 'offline' | 'help';
