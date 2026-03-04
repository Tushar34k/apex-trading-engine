import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { WebSocketEvent } from '@/types';
import { getAccessToken } from './api';

const WS_URL = import.meta.env.VITE_WS_URL || 'http://localhost:8080/ws';

type EventHandler = (event: WebSocketEvent) => void;
type ConnectionStateHandler = (connected: boolean) => void;

class WebSocketClient {
  private client: Client | null = null;
  private subscriptions = new Map<string, { id: string; unsubscribe: () => void }>();
  private handlers = new Map<string, Set<EventHandler>>();
  private connectionHandlers = new Set<ConnectionStateHandler>();
  private reconnectDelay = 1000;
  private maxReconnectDelay = 30000;
  private connected = false;

  connect() {
    if (this.client?.connected) return;

    const token = getAccessToken();

    this.client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: this.reconnectDelay,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      onConnect: () => {
        this.connected = true;
        this.reconnectDelay = 1000;
        this.notifyConnectionState(true);
        this.resubscribeAll();
      },

      onDisconnect: () => {
        this.connected = false;
        this.notifyConnectionState(false);
      },

      onStompError: (frame) => {
        console.error('[WS] STOMP error:', frame.headers['message']);
        this.connected = false;
        this.notifyConnectionState(false);
        // Exponential backoff
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
      },

      onWebSocketClose: () => {
        this.connected = false;
        this.notifyConnectionState(false);
      },
    });

    this.client.activate();
  }

  disconnect() {
    this.subscriptions.forEach((sub) => sub.unsubscribe());
    this.subscriptions.clear();
    this.client?.deactivate();
    this.client = null;
    this.connected = false;
    this.notifyConnectionState(false);
  }

  isConnected() {
    return this.connected;
  }

  subscribe(destination: string, handler: EventHandler) {
    if (!this.handlers.has(destination)) {
      this.handlers.set(destination, new Set());
    }
    this.handlers.get(destination)!.add(handler);

    if (this.client?.connected && !this.subscriptions.has(destination)) {
      this.createSubscription(destination);
    }

    return () => {
      const handlers = this.handlers.get(destination);
      if (handlers) {
        handlers.delete(handler);
        if (handlers.size === 0) {
          this.subscriptions.get(destination)?.unsubscribe();
          this.subscriptions.delete(destination);
          this.handlers.delete(destination);
        }
      }
    };
  }

  onConnectionChange(handler: ConnectionStateHandler) {
    this.connectionHandlers.add(handler);
    return () => this.connectionHandlers.delete(handler);
  }

  // --- Convenience subscribe helpers ---

  subscribeMarket(symbol: string, handler: EventHandler) {
    return this.subscribe(`/topic/market/${symbol}`, handler);
  }

  subscribePositions(handler: EventHandler) {
    return this.subscribe('/user/topic/positions', handler);
  }

  subscribeOrders(handler: EventHandler) {
    return this.subscribe('/user/topic/orders', handler);
  }

  subscribeNotifications(handler: EventHandler) {
    return this.subscribe('/topic/notifications', handler);
  }

  subscribeTrades(handler: EventHandler) {
    return this.subscribe('/user/topic/trades', handler);
  }

  // --- Private ---

  private createSubscription(destination: string) {
    if (!this.client?.connected) return;

    const sub = this.client.subscribe(destination, (message: IMessage) => {
      try {
        const event: WebSocketEvent = JSON.parse(message.body);
        const handlers = this.handlers.get(destination);
        handlers?.forEach((h) => h(event));
      } catch (e) {
        console.error('[WS] Failed to parse message:', e);
      }
    });

    this.subscriptions.set(destination, sub);
  }

  private resubscribeAll() {
    for (const destination of this.handlers.keys()) {
      if (!this.subscriptions.has(destination)) {
        this.createSubscription(destination);
      }
    }
  }

  private notifyConnectionState(connected: boolean) {
    this.connectionHandlers.forEach((h) => h(connected));
  }
}

export const wsClient = new WebSocketClient();
export default wsClient;
