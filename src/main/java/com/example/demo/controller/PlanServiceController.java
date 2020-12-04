package com.example.demo.controller;

import com.example.demo.service.MyUserDetailsService;
import com.example.demo.model.AuthenticationResponse;
import com.example.demo.service.RedisService;
import com.example.demo.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;


@RestController
@ComponentScan("com.example.demo")
public class PlanServiceController {

    private static final String SEP = "____";
    private static final String TYPE = "objectType";
    private static final String ID = "objectId";

    @Autowired
    private RedisService redisService;

//    File schemaFile = new File("src\\main\\java\\com\\example\\demo\\controller\\JSONSchema.json");
    File schemaFile = new File("/Users/yifanhe/IdeaProjects/SpringBootDemo/src/main/java/com/example/demo/controller/JSONSchema.json");
    JSONTokener schemaData;
    {
        try {
            schemaData = new JSONTokener(new FileInputStream(schemaFile));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
    JSONObject jsonSchema = new JSONObject(schemaData);
    Schema schemaValidator = SchemaLoader.load(jsonSchema);

    @RequestMapping(value = "/plans", method = RequestMethod.POST)
    public ResponseEntity<Object> createPlan(@RequestBody String data) throws NoSuchAlgorithmException {
        JSONObject object = new JSONObject(data.trim());

        try {
            schemaValidator.validate(object);
        } catch (ValidationException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }

        String key = object.getString(TYPE) + SEP + object.getString(ID);

        if(redisService.exist(key))
            return new ResponseEntity<>("{\"message\": \"A plan already exists with id " + object.getString(ID) +"\"}",HttpStatus.CONFLICT);

        redisService.postPlan(object);
        redisService.enqueue(object.getString(ID), object, "post");

        object = redisService.getPlan(key);
        HttpHeaders resHeaders = new HttpHeaders();
        resHeaders.setETag("\"" + getSHAString(object.toString()) + "\"");

        return new ResponseEntity<>("{\"objectId\": \""+ object.getString(ID) + "\", \"objectType\": \"" + object.getString(TYPE) + "\", \"message\": \"Created Successfully\", }", resHeaders, HttpStatus.CREATED);
    }

    @RequestMapping(value = "plans/{type}/{id}")
    public ResponseEntity<Object> getPlan(@PathVariable("type") String type, @PathVariable("id") String id, @RequestHeader HttpHeaders headers) {
        String oldETag = headers.getFirst("If-None-Match");

        JSONObject object = redisService.getPlan(type + SEP + id);
        if (object.length() == 0)
            return new ResponseEntity<>("{\"message\": \"No Data Found\"}", HttpStatus.NOT_FOUND); // 404
        String plan = object.toString();

        try {
            String newETag = "\"" + getSHAString(plan) + "\"";

            HttpHeaders resHeaders = new HttpHeaders();
            resHeaders.setETag(newETag);

            if (oldETag == null || oldETag.length() == 0 || !oldETag.equals(newETag))
                return new ResponseEntity<>(plan, resHeaders, HttpStatus.OK);
            else
                return new ResponseEntity<>("{\"objectId\": \""+ object.getString(ID) + "\", \"objectType\": \"" + object.getString(TYPE) + "\", \"message\": \"Not Modified\"}", resHeaders, HttpStatus.NOT_MODIFIED);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return new ResponseEntity<>("{\"message\": \""+ e.getMessage() + "\"}", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/plans/{type}/{id}", method = RequestMethod.DELETE)
    public ResponseEntity<Object> delete(@PathVariable("type") String type, @PathVariable("id") String id) {
        redisService.enqueue(type + SEP + id);
        JSONObject object = redisService.deletePlan(type + SEP + id);

        if(object.length() == 0)
            return new ResponseEntity<>("{\"message\": \"No Data Found\"}", HttpStatus.GONE); //410

        return ResponseEntity.ok().body(object.toString()); //200
    }

    @RequestMapping(value = "/plans/{type}/{id}", method = RequestMethod.PUT)
    public ResponseEntity<Object> updatePlan(@PathVariable("type") String type, @PathVariable("id") String id, @RequestBody String data, @RequestHeader HttpHeaders headers) throws NoSuchAlgorithmException {
        String oldETag = headers.getFirst("If-Match");

        try {
            JSONObject newObject = new JSONObject(data.trim());
            String key = type + SEP + id;
            JSONObject object = redisService.getPlan(key);

            if(object.length() == 0)
                return new ResponseEntity<>("{\"message\": \"No Data Found\"}", HttpStatus.NOT_FOUND); // 404

            HttpHeaders resHeaders = new HttpHeaders();

            String newETag = "\"" + getSHAString(object.toString()) + "\"";

            if(oldETag != null && !oldETag.equals(newETag))
                return new ResponseEntity<>("{\"objectId\": \""+ object.getString(ID) + "\", \"objectType\": \"" + object.getString(TYPE) + "\", \"message\": \"The plan has been modified\", }", resHeaders, HttpStatus.PRECONDITION_FAILED);

            JSONObject deletedObject = redisService.deletePlan(type + SEP + id);
            if(deletedObject.length() == 0)
                return new ResponseEntity<>("{\"message\": \"No Data Found\"}", HttpStatus.GONE); //410

            try {
                schemaValidator.validate(newObject);
            } catch (ValidationException e) {
                return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
            }
            String newKey = newObject.getString(TYPE) + SEP + newObject.getString(ID);

            if(redisService.exist(newKey))
                return new ResponseEntity<>("{\"message\": \"A plan already exists with id " + newObject.getString(ID) +"\"}",HttpStatus.CONFLICT);

            redisService.postPlan(newObject);
//        redisService.enqueue(object.getString(ID), object, "post");

//            newObject = redisService.getPlan(key);
            resHeaders.setETag("\"" + getSHAString(newObject.toString()) + "\"");

            return new ResponseEntity<>("{\"objectId\": \""+ newObject.getString(ID) + "\", \"objectType\": \"" + newObject.getString(TYPE) + "\", \"message\": \"Created Successfully\", }", resHeaders, HttpStatus.CREATED);
        }
        catch(Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("{\"message\": \""+ e.getMessage() + "\"}", HttpStatus.BAD_REQUEST); //
        }
    }

    @RequestMapping(value = "/plans/{type}/{id}", method = RequestMethod.PATCH)
    public ResponseEntity<Object> updatePlanPartial(@PathVariable("type") String type, @PathVariable("id") String id, @RequestBody String data, @RequestHeader HttpHeaders headers) {
        String oldETag = headers.getFirst("If-Match");

        try {
            JSONObject newObject = new JSONObject(data.trim());
            String key = type + SEP + id;
            JSONObject object = redisService.getPlan(key);

            if (object.length() == 0)
                return new ResponseEntity<>("{\"message\": \"No Data Found\"}", HttpStatus.NOT_FOUND); // 404

            HttpHeaders resHeaders = new HttpHeaders();

            String newETag = "\"" + getSHAString(object.toString()) + "\"";
            resHeaders.setETag(newETag);

            if (oldETag != null && !oldETag.equals(newETag))
                return new ResponseEntity<>("{\"objectId\": \""+ object.getString(ID) + "\", \"objectType\": \"" + object.getString(TYPE) + "\", \"message\": \"The plan has been modified\", }", resHeaders, HttpStatus.PRECONDITION_FAILED);

            redisService.patchPlan(key, newObject);
            redisService.enqueue(object.getString(ID), newObject, "patch");

            newETag = "\"" + getSHAString(redisService.getPlan(key).toString()) + "\"";

            resHeaders.setETag(newETag);
//            return new ResponseEntity<>("{\"objectId\": \""+ object.getString(ID) + "\", \"objectType\": \"" + object.getString(TYPE) + "\", \"message\": \"Updated Successfully\", }", resHeaders, HttpStatus.OK);
            return new ResponseEntity<>(redisService.getPlan(key).toString(), resHeaders, HttpStatus.OK);
        }
        catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("{\"message\": \""+ e.getMessage() + "\"}", HttpStatus.BAD_REQUEST); //
        }
    }

    // generate ETag using SHA256 Hash algorithm
    private String getSHAString(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));

        BigInteger number = new BigInteger(1, hash);

        StringBuilder hexString = new StringBuilder(number.toString(16));

        while (hexString.length() < 32) {
            hexString.insert(0, '0');
        }

        return hexString.toString();
    }



    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private JwtUtil jwtTokenUtil;
    @Autowired
    private MyUserDetailsService userDetailsService;

    @RequestMapping(value = "token", method = RequestMethod.POST)
    public ResponseEntity<?> createAuthenticationToken() throws Exception {

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken("foo", "foo")
            );
        }
        catch (BadCredentialsException e) {
            throw new Exception("Incorrect username or password", e);
        }


        final UserDetails userDetails = userDetailsService
                .loadUserByUsername("foo");

        final String jwt = jwtTokenUtil.generateToken(userDetails);

        return ResponseEntity.ok(new AuthenticationResponse(jwt));
    }

}
