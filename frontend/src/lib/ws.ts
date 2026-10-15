import { Client } from '@stomp/stompjs';
import { getAccessToken } from './api';

/** Live campaign progress over STOMP: /topic/ws/{workspaceId}/campaign/{id}. Falls back silently if the socket is unavailable (callers also poll). */
export function subscribeCampaign(workspaceId: number, campaignId: number, onMessage: (m: any) => void): () => void {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const client = new Client({
    brokerURL: `${proto}://${location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
    reconnectDelay: 5000,
    onConnect: () => {
      client.subscribe(`/topic/ws/${workspaceId}/campaign/${campaignId}`, (f) => {
        try { onMessage(JSON.parse(f.body)); } catch { /* ignore malformed frames */ }
      });
    },
  });
  client.activate();
  return () => { client.deactivate(); };
}
