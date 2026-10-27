package email

import (
	"fmt"
	"net/smtp"
)

type Sender interface {
	Send(to, subject, body string) error
}

type SMTP struct {
	Host string
	Port int
	User string
	Pass string
	From string
}

func (s *SMTP) Send(to, subject, body string) error {
	if s.Host == "" {
		return fmt.Errorf("smtp not configured")
	}
	addr := fmt.Sprintf("%s:%d", s.Host, s.Port)
	auth := smtp.PlainAuth("", s.User, s.Pass, s.Host)
	msg := []byte("From: " + s.From + "\r\n" +
		"To: " + to + "\r\n" +
		"Subject: " + subject + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=\"utf-8\"\r\n\r\n" +
		body + "\r\n")
	return smtp.SendMail(addr, auth, s.From, []string{to}, msg)
}

type DevNull struct{}

func (d *DevNull) Send(to, subject, body string) error {
	// Do not send, just return nil to indicate "attempted" but not actually sent
	// Caller must handle based on configuration
	return fmt.Errorf("email delivery not configured")
}
