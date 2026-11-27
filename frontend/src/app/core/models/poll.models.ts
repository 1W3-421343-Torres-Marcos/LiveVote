export type PollStatus = 'OPEN' | 'CLOSED';

export interface PollOptionResponse {
  id: string;
  text: string;
  displayOrder: number;
}

export interface PollResponse {
  id: string;
  title: string;
  status: PollStatus;
  options: PollOptionResponse[];
  createdAt: string;
  closedAt: string | null;
}

export interface CreatePollRequest {
  title: string;
  options: string[];
}

export interface CastVoteRequest {
  optionId: string;
  deviceId: string;
}

export interface VoteResponse {
  voteId: string;
  pollId: string;
  optionId: string;
  castAt: string;
}
