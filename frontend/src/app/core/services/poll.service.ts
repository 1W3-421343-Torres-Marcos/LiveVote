import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CastVoteRequest,
  CreatePollRequest,
  PollResponse,
  VoteResponse,
} from '../models/poll.models';

@Injectable({ providedIn: 'root' })
export class PollService {
  private readonly http = inject(HttpClient);

  getMyPolls(): Observable<PollResponse[]> {
    return this.http.get<PollResponse[]>('/api/polls/mine');
  }

  createPoll(request: CreatePollRequest): Observable<PollResponse> {
    return this.http.post<PollResponse>('/api/polls', request);
  }

  closePoll(pollId: string): Observable<PollResponse> {
    return this.http.post<PollResponse>(`/api/polls/${encodeURIComponent(pollId)}/close`, {});
  }

  getPublicPoll(pollId: string): Observable<PollResponse> {
    return this.http.get<PollResponse>(`/api/polls/${encodeURIComponent(pollId)}`);
  }

  castVote(pollId: string, request: CastVoteRequest): Observable<VoteResponse> {
    return this.http.post<VoteResponse>(`/api/polls/${encodeURIComponent(pollId)}/votes`, request);
  }
}
