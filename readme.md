# Proxy Sieciowe TCP / UDP

Wielowątkowy węzeł pośredniczący (Proxy) realizujący komunikację klient-serwer z dynamiczną detekcją protokołu (TCP/UDP) oraz agregacją i dystrybucją zapytań w rozproszonej bazie klucz-wartość.

## Spis treści

* [Opis projektu](#opis-projektu)
* [Główne funkcjonalności](#główne-funkcjonalności)
* [Protokół komunikacji](#protokół-komunikacji)

  * [Format wiadomości](#format-wiadomości)
  * [Obsługiwane komendy](#obsługiwane-komendy)
* [Architektura i zasada działania](#architektura-i-zasada-działania)

  * [Inicjalizacja i detekcja protokołu](#inicjalizacja-i-detekcja-protokołu)
  * [Wielowątkowa obsługa zapytań](#wielowątkowa-obsługa-zapytań)
  * [Obsługa błędów i timeouty](#obsługa-błędów-i-timeouty)
* [Struktura projektu](#struktura-projektu)
* [Wymagania i kompilacja](#wymagania-i-kompilacja)
* [Uruchomienie](#uruchomienie)
* [Przykłady użycia](#przykłady-użycia)



\---

## Opis projektu

Projekt stanowi węzeł pośredniczący w architekturze sieciowej, który pełni rolę inteligentnej bramki pomiędzy klientami a wieloma węzłami serwerowymi. Proxy nasłuchuje równolegle na tym samym porcie dla zapytań przychodzących w protokołach **TCP** oraz **UDP**, a następnie przekazuje je do odpowiednich serwerów docelowych w protokole, który został dla nich zidentyfikowany.

## Główne funkcjonalności

* **Dual-stack na jednym porcie**: Równoczesna obsługa ruchu TCP (`ServerSocket`) i UDP (`DatagramSocket`) na wybranym porcie.
* **Dynamiczna detekcja protokołu**: Automatyczne badanie dostępności i protokołu docelowego węzła (TCP vs UDP) podczas startu.
* **Wielowątkowość**: Obsługa wielu klientów równolegle z wykorzystaniem puli wątków (`Executors.newCachedThreadPool()`).
* **Agregacja wyników (`GET NAMES`)**: Odpytanie wszystkich podłączonych węzłów i usunięcie duplikatów z listy kluczy.
* **Sekwencyjny fallback (`GET VALUES`, `SET`)**: Odpytywanie serwerów po kolei do momentu uzyskania poprawnej odpowiedzi (`OK`).
* **Kaskadowe zamykanie sieci (`QUIT`)**: Propagacja sygnału zakończenia pracy do wszystkich zarejestrowanych serwerów i bezpieczne wyłączenie Proxy.

\---

## Protokół komunikacji

### Format wiadomości

* Kodowanie znaków: **UTF-8**.
* Każda komenda przesyłana przez sieć musi być zakończona znakami nowej linii: `\\r\\n`.
* Odpowiedź sukcesu rozpoczyna się od prefiksu: `OK`.
* W przypadku błędu, nieznanej komendy lub braku danych zwracane jest: `NA`.

### Obsługiwane komendy

|Komenda|Opis|Zachowanie Proxy|Przykładowa odpowiedź|
|-|-|-|-|
|`GET NAMES`|Pobranie listy wszystkich unikalnych kluczy.|Proxy pyta wszystkie aktywne serwery, agreguje unikalne klucze do posortowanego zbioru (`TreeSet`) i zwraca wspólną listę.|`OK 3 keyA keyB keyC` lub `NA`|
|`GET VALUES <key>`|Pobranie wartości dla danego klucza.|Sekwencyjne odpytywanie serwerów w kolejności ich konfiguracji. Zwraca pierwszą odpowiedź zaczynającą się od `OK`.|`OK <wartość>` lub `NA`|
|`SET <key> <val>`|Zapisanie danych na serwerze.|Przekazanie żądania sekwencyjnie do serwerów aż do uzyskania potwierdzenia zapisu.|`OK` lub `NA`|
|`QUIT`|Zamknięcie węzłów i Proxy.|Rozsyła komendę `QUIT` do wszystkich serwerów, odsyła potwierdzenie do klienta i kończy pracę procesu.|`Terminating Proxy` (TCP) / `QUIT` (UDP)|

\---

## Architektura i zasada działania

### Inicjalizacja i detekcja protokołu

1. Podczas startu aplikacji Proxy iteruje po liście serwerów podanych w parametrach `-server`.
2. Dla każdego serwera wysyła zapytanie testowe (`testOfConnection\\r\\n`) najpierw przez **TCP**:

   * Jeżeli połączenie i odczyt zakończą się powodzeniem, węzeł oznaczany jest jako `ServerType.TCP`.
3. Jeśli próba TCP zawiedzie, wysyłany jest datagram testowy przez **UDP**:

   * Jeśli serwer odpowie w czasie do 2 sekund, oznaczany jest jako `ServerType.UDP`.
4. W przypadku braku odpowiedzi w obu próbach serwer jest pomijany przy dalszym routingu (odrzucenie nieaktywnych węzłów).

### Wielowątkowa obsługa zapytań

* Po inicjalizacji uruchamiany jest wątek dedykowany pętli UDP (`runUdpLoop`).
* Wątek główny w pętli `while (running)` akceptuje nowe połączenia TCP (`accept()`) i przekazuje każdego klienta jako osobne zadanie do puli `ExecutorService`.

### Obsługa błędów i timeouty

* Zarówno gniazda TCP (`Socket.setSoTimeout(2000)`), jak i UDP (`DatagramSocket.setSoTimeout(2000)`) mają skonfigurowany 2-sekundowy limit czasu odpowiedzi.
* Pozwala to zapobiec blokowaniu Proxy w przypadku awarii jednego z serwerów docelowych lub podczas rozgłaszania komendy `QUIT`.

\---

## Struktura projektu

```text
.
├── Proxy.java        # Główna klasa proxy, routing, obsługa socketów TCP/UDP
├── ServerNode.java   # Model węzła docelowego (host, port, typ protokołu)
├── serverType.java   # Enum definiujący obsługiwane typy (TCP, UDP)
└── README.md         # Dokumentacja projektu
```

\---

## Wymagania i kompilacja

* **Java JDK**: wersja 8 lub nowsza (zalecana JDK 11+)
* Kompilator `javac` dostępny w zmiennej środowiskowej `PATH`.

Kompilacja wszystkich plików:

```bash
javac Proxy.java ServerNode.java serverType.java
```

\---

## Uruchomienie

Program przyjmuje port nasłuchu oraz dowolną liczbę węzłów serwerowych podanych w formacie:

```bash
java Proxy -port <port\_proxy> -server <host1> <port1> \[-server <host2> <port2> ...]
```

### Przykład uruchomienia:

```bash
java Proxy -port 9000 -server 127.0.0.1 9001 -server 127.0.0.1 9002
```

\---

## Przykłady użycia

Testowanie za pomocą narzędzia `netcat` (lub `ncat` / `telnet`):

### 1\. Połączenie przez TCP:

```bash
nc localhost 9000
```

Wysyłane komendy:

```text
SET user:1 jan
OK
GET VALUES user:1
OK jan
GET NAMES
OK 1 user:1
QUIT
Terminating Proxy
```

### 2\. Połączenie przez UDP:

```bash
nc -u localhost 9000
```

Wysyłane komendy:

```text
GET NAMES
OK 1 user:1
```

\---

