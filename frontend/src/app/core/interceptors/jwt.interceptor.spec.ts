import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { jwtInterceptor } from './jwt.interceptor';

describe('jwtInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authService: AuthService;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([jwtInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('should attach Bearer header on protected API calls and skip auth endpoints', () => {
    localStorage.setItem('livevote_jwt_token', 'jwt-abc');

    http.get('/api/polls/mine').subscribe();
    const protectedReq = httpMock.expectOne('/api/polls/mine');
    expect(protectedReq.request.headers.get('Authorization')).toBe('Bearer jwt-abc');
    protectedReq.flush([]);

    http.post('/api/auth/login', { email: 'a@b.com', password: 'Password123!' }).subscribe();
    const loginReq = httpMock.expectOne('/api/auth/login');
    expect(loginReq.request.headers.has('Authorization')).toBeFalse();
    loginReq.flush({});
  });

  it('should clear session and navigate to /login on 401 response', () => {
    localStorage.setItem('livevote_jwt_token', 'expired-jwt');
    const clearSpy = spyOn(authService, 'clearSession').and.callThrough();
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    http.get('/api/polls/mine').subscribe({
      next: () => fail('expected 401 error'),
      error: (err) => {
        expect(err.status).toBe(401);
      },
    });

    const req = httpMock.expectOne('/api/polls/mine');
    req.flush({ status: 401, message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });

    expect(clearSpy).toHaveBeenCalled();
    expect(localStorage.getItem('livevote_jwt_token')).toBeNull();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });
});
