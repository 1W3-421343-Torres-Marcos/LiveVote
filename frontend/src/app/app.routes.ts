import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';
import { LoginComponent } from './features/auth/login.component';
import { RegisterComponent } from './features/auth/register.component';
import { CreatePollComponent } from './features/polls/create-poll.component';
import { MyPollsComponent } from './features/polls/my-polls.component';
import { LiveResultsComponent } from './features/results/live-results.component';
import { VotePollComponent } from './features/vote/vote-poll.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: 'register', component: RegisterComponent, canActivate: [guestGuard] },
  { path: 'polls', component: MyPollsComponent, canActivate: [authGuard] },
  { path: 'polls/new', component: CreatePollComponent, canActivate: [authGuard] },
  { path: 'vote/:id', component: VotePollComponent },
  { path: 'results/:id', component: LiveResultsComponent },
  { path: '', redirectTo: 'polls', pathMatch: 'full' },
  { path: '**', redirectTo: 'login' },
];
