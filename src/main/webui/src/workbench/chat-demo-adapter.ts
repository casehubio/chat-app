import type {
  QhorusMessage, QhorusChannel, Reaction, ChannelMember, PresenceState,
  MessageType, ActorType,
} from '@casehubio/blocks-ui-channel-activity';

interface WsOp {
  op: 'snapshot' | 'append' | 'replace' | 'remove';
  dataset: string;
  rows?: unknown[][];
  row?: unknown[];
  key?: string;
}

type ChangeListener = (dataset: string) => void;

export class ChatDemoAdapter {
  channels: QhorusChannel[] = [];
  messages: QhorusMessage[] = [];
  reactions: Reaction[] = [];
  members: ChannelMember[] = [];
  presence: PresenceState[] = [];

  private _listeners: ChangeListener[] = [];

  onChange(fn: ChangeListener) { this._listeners.push(fn); }
  offChange(fn: ChangeListener) { this._listeners = this._listeners.filter(l => l !== fn); }
  private _notify(dataset: string) { for (const fn of this._listeners) fn(dataset); }

  applyOp(op: WsOp) {
    switch (op.dataset) {
      case 'channels': this._applyChannels(op); break;
      case 'messages': this._applyMessages(op); break;
      case 'reactions': this._applyReactions(op); break;
      case 'members': this._applyMembers(op); break;
      case 'presence': this._applyPresence(op); break;
    }
    this._notify(op.dataset);
  }

  private _applyChannels(op: WsOp) {
    if (op.op === 'snapshot') {
      this.channels = (op.rows ?? []).map(r => this._toChannel(r));
    } else if (op.op === 'append' && op.rows) {
      this.channels = [...this.channels, ...op.rows.map(r => this._toChannel(r))];
    } else if (op.op === 'remove' && op.key) {
      this.channels = this.channels.filter(c => c.id !== op.key);
    }
  }

  private _toChannel(row: unknown[]): QhorusChannel {
    return {
      id: row[0] as string,
      name: row[1] as string,
      description: (row[2] as string) || undefined,
      semantic: 'APPEND',
      paused: false,
    };
  }

  private _applyMessages(op: WsOp) {
    if (op.op === 'snapshot') {
      this.messages = (op.rows ?? []).map(r => this._toMessage(r));
    } else if (op.op === 'append' && op.rows) {
      this.messages = [...this.messages, ...op.rows.map(r => this._toMessage(r))];
    } else if (op.op === 'remove' && op.key) {
      this.messages = this.messages.filter(m => m.id !== op.key);
    }
    this._recomputeReplyCounts();
  }

  private _recomputeReplyCounts() {
    const counts = new Map<string, number>();
    for (const m of this.messages) {
      if (m.inReplyTo) {
        counts.set(m.inReplyTo, (counts.get(m.inReplyTo) ?? 0) + 1);
      }
    }
    this.messages = this.messages.map(m => ({
      ...m,
      replyCount: counts.get(m.id) ?? 0,
    }));
  }

  private _toMessage(row: unknown[]): QhorusMessage {
    return {
      id: row[1] as string,
      channelId: row[0] as string,
      sender: row[3] as string,
      messageType: (row[6] as string as MessageType) || 'EVENT',
      actorType: (row[7] as string as ActorType) || 'HUMAN',
      content: row[4] as string,
      topic: (row[8] as string) || 'General',
      correlationId: undefined,
      inReplyTo: (row[2] as string) || undefined,
      replyCount: 0,
      artefactRefs: [],
      createdAt: row[5] as string,
    };
  }

  private _applyReactions(op: WsOp) {
    if (op.op === 'snapshot') {
      this.reactions = (op.rows ?? []).map(r => ({
        messageId: r[0] as string, emoji: r[1] as string,
        actorId: '', createdAt: '',
      }));
    } else if (op.op === 'append' && op.rows) {
      this.reactions = [...this.reactions, ...op.rows.map(r => ({
        messageId: r[0] as string, emoji: r[1] as string,
        actorId: '', createdAt: '',
      }))];
    } else if (op.op === 'remove' && op.key) {
      const sep = op.key.indexOf(':');
      if (sep >= 0) {
        const msgId = op.key.substring(0, sep);
        const emoji = op.key.substring(sep + 1);
        this.reactions = this.reactions.filter(r =>
          !(r.messageId === msgId && r.emoji === emoji)
        );
      } else {
        this.reactions = this.reactions.filter(r => r.messageId !== op.key);
      }
    }
  }

  private _applyMembers(op: WsOp) {
    if (op.op === 'snapshot') {
      this.members = (op.rows ?? []).map(r => ({
        channelId: r[1] as string, memberId: r[2] as string,
        displayName: r[3] as string,
        role: (r[4] as ChannelMember['role']) || 'PARTICIPANT',
      }));
    } else if (op.op === 'append' && op.rows) {
      this.members = [...this.members, ...op.rows.map(r => ({
        channelId: r[1] as string, memberId: r[2] as string,
        displayName: r[3] as string,
        role: (r[4] as ChannelMember['role']) || 'PARTICIPANT',
      }))];
    } else if (op.op === 'remove' && op.key) {
      const sep = op.key.indexOf(':');
      if (sep >= 0) {
        const chId = op.key.substring(0, sep);
        const memId = op.key.substring(sep + 1);
        this.members = this.members.filter(m =>
          !(m.channelId === chId && m.memberId === memId)
        );
      } else {
        this.members = this.members.filter(m => m.memberId !== op.key);
      }
    }
  }

  private _applyPresence(op: WsOp) {
    if (op.op === 'snapshot') {
      this.presence = (op.rows ?? []).map(r => ({
        memberId: r[0] as string,
        status: r[1] as PresenceState['status'],
        lastSeenAt: (r[2] as string) || undefined,
      }));
    } else if (op.op === 'replace' && op.row) {
      this.presence = this.presence.map(p =>
        p.memberId === op.row![0]
          ? { ...p, status: op.row![1] as PresenceState['status'], lastSeenAt: (op.row![2] as string) || undefined }
          : p
      );
    }
  }
}
