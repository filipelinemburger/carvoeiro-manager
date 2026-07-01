package br.com.carvoeiromanager.auth;

import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final SaveGameRepository saveGameRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       SaveGameRepository saveGameRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.saveGameRepository = saveGameRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Permanently deletes a user and every piece of data tied to their account:
     * all save games and (via ON DELETE CASCADE) their clubs, players, fixtures,
     * finances, transfers, news and season history.
     */
    @Transactional
    public void deleteAccount(UUID userId) {
        saveGameRepository.deleteByUserId(userId);
        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw usernameTaken(request.username());
        }
        try {
            User user = User.create(request.username(), passwordEncoder.encode(request.password()));
            // saveAndFlush (not save) forces the INSERT now, so a concurrent duplicate
            // surfaces as DataIntegrityViolationException here, not at transaction commit.
            userRepository.saveAndFlush(user);
            return toResponse(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race: another request inserted the same username between the check and the insert.
            throw usernameTaken(request.username());
        }
    }

    private ApiException usernameTaken(String username) {
        return new ApiException("USERNAME_TAKEN",
                "Username '" + username + "' is already taken", HttpStatus.CONFLICT);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                .orElseThrow(() -> new ApiException("INVALID_CREDENTIALS",
                        "Invalid username or password", HttpStatus.UNAUTHORIZED));
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return new AuthResponse(token, user.getUsername(), user.getId());
    }
}
