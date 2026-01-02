
## 签名文件生成

生成platform.priv.pem
```shell
openssl pkcs8 -in platform.pk8 -inform DER -outform PEM -out platform.priv.pem -nocrypt
```

生成pkcs12格式的密钥文件,生成platform.pk12文件，最后的rk3576是keystore的alias，这里默认为rk3576， platform.pk12是pk12文件名，需要输入两次密码（密码rk3576），
```shell
openssl pkcs12 -export -in platform.x509.pem -inkey platform.priv.pem -out platform.pk12 -name rk3576
```

生成keystore文件
```shell
keytool -importkeystore -destkeystore platform.keystore -srckeystore platform.pk12 -srcstoretype PKCS12 -srcstorepass rk3576 -alias rk3576
```
