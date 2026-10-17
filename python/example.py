# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Shotmetrix Kemmer GmbH
"""Example: render a Shotmetrix QR code as an image.   python3 example.py <public key> > code.png
Also needs the "qrcode" package (pip install qrcode pillow)."""
import sys, time, qrcode
from qrcode.util import QRData, MODE_ALPHA_NUM, MODE_8BIT_BYTE
from shotmetrix_qrcode import qr_code_url, URL_PREFIX

shots = [{"x": 0.4, "y": -1.1, "value": 10.6}, {"x": -2.0, "y": 0.8, "value": 10.1}, {"x": 1.2, "y": 2.3, "value": 9.9},
         {"x": -0.6, "y": -0.3, "value": 10.7}, {"x": 3.1, "y": -0.9, "value": 9.7}]
start = int(time.time()) - 600
for i, s in enumerate(shots):
    s["time"] = start + 120 + 52.5 * i          # shot time, Unix seconds
url = qr_code_url(sys.argv[1], discipline=1, decimal=True, time=start, shots=shots)
qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, border=4, box_size=6)
# URL prefix as a byte segment, code as an alphanumeric segment
qr.add_data(QRData(URL_PREFIX, mode=MODE_8BIT_BYTE)); qr.add_data(QRData(url[len(URL_PREFIX):], mode=MODE_ALPHA_NUM)); qr.make(fit=True)
print(f"QR version {qr.version} ({qr.version * 4 + 17} modules), {len(url)} characters", file=sys.stderr)
qr.make_image().save(sys.stdout.buffer)
