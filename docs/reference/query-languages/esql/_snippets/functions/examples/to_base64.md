% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
ROW a = "elastic"
| EVAL e = TO_BASE64(a)
```

| a:keyword | e:keyword |
| --- | --- |
| elastic | ZWxhc3RpYw== |


