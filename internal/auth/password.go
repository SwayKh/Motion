package auth

import (
	"errors"
	"strings"
	"unicode"

	"golang.org/x/crypto/bcrypt"
)

var ErrWeakPassword = errors.New("password does not meet policy requirements")

func HashPassword(password string) (string, error) {
	if err := ValidatePassword(password); err != nil {
		return "", err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(hash), nil
}

func CheckPassword(password, hash string) error {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
}

func ValidatePassword(password string) error {
	if len(password) < 8 {
		return ErrWeakPassword
	}
	var hasLetter, hasDigit bool
	for _, r := range password {
		if unicode.IsLetter(r) {
			hasLetter = true
		}
		if unicode.IsDigit(r) {
			hasDigit = true
		}
	}
	if !hasLetter || !hasDigit {
		return ErrWeakPassword
	}
	return nil
}

func NormalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

func ValidateEmail(email string) bool {
	email = NormalizeEmail(email)
	if email == "" || len(email) > 254 {
		return false
	}
	if !strings.Contains(email, "@") {
		return false
	}
	parts := strings.Split(email, "@")
	if len(parts) != 2 {
		return false
	}
	if parts[0] == "" || parts[1] == "" || !strings.Contains(parts[1], ".") {
		return false
	}
	return true
}
