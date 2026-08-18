from binascii import unhexlify

from smartcard.System import readers

AID = "48656C6C6F01"
SELECT = "00A4040006" + AID
HELLO = "8000000000"

conn = readers()[0].createConnection()
conn.connect()
conn.transmit(list(unhexlify(SELECT)))
data, sw1, sw2 = conn.transmit(list(unhexlify(HELLO)))
print(bytes(data).decode(), f"{sw1:02X}{sw2:02X}")
