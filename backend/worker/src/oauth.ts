// The service-account JWT's claims, shared by the Worker (google-auth.ts, WebCrypto) and the
// Apps Script backup (Utilities.computeRsaSha256Signature). Pure: no Web APIs.
export const TOKEN_URL = "https://oauth2.googleapis.com/token";
/** One token covers Firestore and FCM; nothing else. */
export const SCOPES = [
  "https://www.googleapis.com/auth/datastore",
  "https://www.googleapis.com/auth/firebase.messaging",
].join(" ");
export const JWT_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";

export function jwtClaims(clientEmail: string, nowSec: number) {
  return {
    iss: clientEmail,
    scope: SCOPES,
    aud: TOKEN_URL,
    iat: nowSec - 30,   // absorbs small clock skew
    exp: nowSec + 3300, // must be <= iat + 3600
  };
}
