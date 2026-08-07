package server

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"log"
	"math/big"
	"os"
	"path"
	"sync/atomic"
	"time"
)

// Based on: https://github.com/ulixee/hero/blob/main/mitm-socket/go/generate_cert.go

const (
	caFile    = "ca.der"
	caKeyFile = "caKey.der"

	// configDirName is the directory under os.UserConfigDir that holds the CA and, written by the
	// Java side, rules.json. legacyConfigDirName is what setups predating the -plus rename used.
	configDirName       = "burp-awesome-tls-plus"
	legacyConfigDirName = "burp-awesome-tls"
)

// While generating a new certificate, in order to get a unique serial
// number every time we increment this value.
var currentSerialNumber = time.Now().Unix()

func getAbsoluteFilePath(file string) string {
	userConfigDir, err := os.UserConfigDir()
	if err != nil {
		return file
	}

	return resolveConfigFile(userConfigDir, file)
}

// resolveConfigFile returns where file lives under baseDir, creating the directory and adopting
// the equivalent file from a setup predating the -plus rename.
//
// Adopting matters for the CA: regenerating it would make every client that trusts the old one
// start failing. The condition is the file rather than the directory, because the Java side
// creates the same directory for rules.json whenever it happens to start first.
func resolveConfigFile(baseDir, file string) string {
	configDir := path.Join(baseDir, configDirName)
	_ = os.Mkdir(configDir, 0o700)
	target := path.Join(configDir, file)

	if _, err := os.Stat(target); errors.Is(err, os.ErrNotExist) {
		legacy := path.Join(baseDir, legacyConfigDirName, file)
		if _, err := os.Stat(legacy); err == nil {
			_ = os.Rename(legacy, target)
		}
	}

	return target
}

func readCertFromDisk(file string) (*x509.Certificate, error) {
	bytes, err := os.ReadFile(getAbsoluteFilePath(file))
	if err != nil {
		return nil, err
	}

	cert, err := x509.ParseCertificate(bytes)
	if err != nil {
		return nil, err
	}

	return cert, nil
}

func readPrivateKeyFromDisk(file string) (*rsa.PrivateKey, error) {
	bytes, err := os.ReadFile(getAbsoluteFilePath(file))
	if err != nil {
		return nil, err
	}

	key, err := x509.ParsePKCS8PrivateKey(bytes)
	if err != nil {
		return nil, err
	}

	privatePkcs8RsaKey, ok := key.(*rsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("Pkcs8 contained non-RSA key. Expected RSA key.")
	}

	return privatePkcs8RsaKey, nil
}

// NewCertificateAuthority creates a new CA certificate and associated private key, unless it already exists on disk.
func NewCertificateAuthority() (*x509.Certificate, *rsa.PrivateKey, error) {
	certFromDisk, err := readCertFromDisk(caFile)

	if err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Printf("Error reading cert from disk %s %e", caFile, err)
	} else if err == nil {
		keyFromDisk, err := readPrivateKeyFromDisk(caKeyFile)
		if err != nil {
			log.Printf("Error reading private key from disk %s %e", caKeyFile, err)
		} else {
			return certFromDisk, keyFromDisk, nil
		}
	}

	// Generating the private key that will be used for domain certificates
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, nil, err
	}
	pub := priv.Public()

	// Subject Key Identifier support for end entity certificate.
	// https://tools.ietf.org/html/rfc3280#section-4.2.1.2
	pkixpub, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return nil, nil, err
	}
	h := sha1.New()
	_, err = h.Write(pkixpub)
	if err != nil {
		return nil, nil, err
	}
	keyID := h.Sum(nil)

	// Increment the serial number
	serial := atomic.AddInt64(&currentSerialNumber, 1)

	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(serial),
		Subject: pkix.Name{
			CommonName:   "Awesome TLS",
			Organization: []string{"Sleeyax"},
		},
		SubjectKeyId:          keyID,
		KeyUsage:              x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		NotBefore:             time.Now().AddDate(-1, 0, 0),
		NotAfter:              time.Now().AddDate(1, 0, 0),
		DNSNames:              []string{"awesometls", "localhost"},
		IsCA:                  true,
	}

	raw, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, priv)
	if err != nil {
		return nil, nil, err
	}

	err = os.WriteFile(getAbsoluteFilePath(caFile), raw, 0o600)
	if err != nil {
		return nil, nil, err
	}

	privBytes, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, nil, err
	}

	err = os.WriteFile(getAbsoluteFilePath(caKeyFile), privBytes, 0o600)
	if err != nil {
		return nil, nil, err
	}

	// Parse certificate bytes so that we have a leaf certificate.
	x509c, err := x509.ParseCertificate(raw)
	if err != nil {
		return nil, nil, err
	}

	return x509c, priv, nil
}
