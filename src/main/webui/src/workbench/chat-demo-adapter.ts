import type {
  QhorusMessage, QhorusChannel, QhorusTopic, Reaction, ChannelMember, PresenceState,
  MessageType, ActorType, ArtefactRef, CommitmentState, TopicState,
} from '@casehubio/blocks-ui-channel-activity';
import type { CommitmentRecord } from '../types.js';

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
  topics: QhorusTopic[] = [];
  messages: QhorusMessage[] = [];
  reactions: Reaction[] = [];
  members: ChannelMember[] = [];
  presence: PresenceState[] = [];
  commitments: Map<string, CommitmentRecord> = new Map();

  private _listeners: ChangeListener[] = [];

  onChange(fn: ChangeListener) { this._listeners.push(fn); }
  offChange(fn: ChangeListener) { this._listeners = this._listeners.filter(l => l !== fn); }
  private _notify(dataset: string) { for (const fn of this._listeners) fn(dataset); }

  applyOp(op: WsOp) {
    switch (op.dataset) {
      case 'channels': this._applyChannels(op); break;
      case 'topics': this._applyTopics(op); break;
      case 'messages': this._applyMessages(op); break;
      case 'reactions': this._applyReactions(op); break;
      case 'members': this._applyMembers(op); break;
      case 'presence': this._applyPresence(op); break;
      case 'commitments': this._applyCommitments(op); break;
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

  private _applyTopics(op: WsOp) {
    if (op.op === 'snapshot') {
      this.topics = (op.rows ?? []).map(r => this._toTopic(r));
    } else if (op.op === 'append' && op.rows) {
      this.topics = [...this.topics, ...op.rows.map(r => this._toTopic(r))];
    } else if (op.op === 'replace' && op.row && op.key) {
      this.topics = this.topics.map(t =>
        t.id === op.key ? this._toTopic(op.row!) : t
      );
    } else if (op.op === 'remove' && op.key) {
      this.topics = this.topics.filter(t => t.id !== op.key);
    }
  }

  private _toTopic(row: unknown[]): QhorusTopic {
    return {
      id: row[0] as string,
      channelId: row[1] as string,
      name: row[2] as string,
      state: row[3] as TopicState,
      messageCount: Number(row[4]) || 0,
      latestActivityTs: (row[5] as string) || undefined,
      createdAt: row[6] as string,
    };
  }

  private _resolveTopicName(topicId: string | null | undefined): string {
    if (!topicId) return '';
    const topic = this.topics.find(t => t.id === topicId);
    return topic?.name ?? '';
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
    let artefactRefs: ArtefactRef[] = [];
    try {
      const refsStr = row[10] as string;
      if (refsStr && refsStr !== '[]') artefactRefs = JSON.parse(refsStr);
    } catch (_e) { /* ignore parse errors */ }
    return {
      id: row[1] as string,
      channelId: row[0] as string,
      sender: row[3] as string,
      messageType: (row[6] as string as MessageType) || 'EVENT',
      actorType: (row[7] as string as ActorType) || 'HUMAN',
      content: row[4] as string,
      topicId: (row[8] as string) || '',
      topic: this._resolveTopicName(row[8] as string),
      correlationId: (row[9] as string) || undefined,
      inReplyTo: (row[2] as string) || undefined,
      replyCount: 0,
      artefactRefs,
      target: (row[11] as string) || undefined,
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

  private _applyCommitments(op: WsOp) {
    if (op.op === 'snapshot') {
      this.commitments = new Map();
      for (const r of op.rows ?? []) {
        this.commitments.set(r[0] as string, {
          state: r[2] as CommitmentState,
          deadline: (r[3] as string) || undefined,
          acknowledgedAt: (r[4] as string) || undefined,
          createdAt: r[5] as string,
          updatedAt: r[6] as string,
        });
      }
    } else if (op.op === 'replace' && op.row) {
      this.commitments = new Map(this.commitments);
      this.commitments.set(op.row[0] as string, {
        state: op.row[2] as CommitmentState,
        deadline: (op.row[3] as string) || undefined,
        acknowledgedAt: (op.row[4] as string) || undefined,
        createdAt: op.row[5] as string,
        updatedAt: op.row[6] as string,
      });
    } else if (op.op === 'append' && op.rows) {
      this.commitments = new Map(this.commitments);
      for (const r of op.rows) {
        this.commitments.set(r[0] as string, {
          state: r[2] as CommitmentState,
          deadline: (r[3] as string) || undefined,
          acknowledgedAt: (r[4] as string) || undefined,
          createdAt: r[5] as string,
          updatedAt: r[6] as string,
        });
      }
    }
  }
}
