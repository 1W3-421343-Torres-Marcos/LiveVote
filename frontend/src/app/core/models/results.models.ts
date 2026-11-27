import { PollStatus } from './poll.models';

export interface OptionResultResponse {
  optionId: string;
  text: string;
  displayOrder: number;
  votes: number;
}

export interface PollResultsResponse {
  pollId: string;
  title: string;
  status: PollStatus;
  totalVotes: number;
  options: OptionResultResponse[];
  createdAt: string;
  closedAt: string | null;
}

export interface HeartbeatEvent {
  timestamp: string;
}

export type ResultsStreamEvent =
  | { type: 'results'; payload: PollResultsResponse }
  | { type: 'heartbeat'; payload: HeartbeatEvent }
  | { type: 'reconnecting' };
