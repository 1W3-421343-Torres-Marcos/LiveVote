import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('should store JWT and user in localStorage on login and clear on logout', () => {
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    service.login({ email: 'user@livevote.local', password: 'Password123!' }).subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush({
      token: 'test-token-value',
      tokenType: 'Bearer',
      expiresIn: 7200,
      userId: 'user-1',
      email: 'user@livevote.local',
    });

    expect(localStorage.getItem('livevote_jwt_token')).toBe('test-token-value');
    expect(service.getToken()).toBe('test-token-value');
    expect(service.isAuthenticated()).toBeTrue();
    expect(service.currentUser()?.email).toBe('user@livevote.local');

    service.logout();

    expect(localStorage.getItem('livevote_jwt_token')).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.currentUser()).toBeNull();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });
});
